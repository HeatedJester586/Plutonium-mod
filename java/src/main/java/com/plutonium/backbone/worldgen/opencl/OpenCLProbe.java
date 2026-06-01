package com.plutonium.backbone.worldgen.opencl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CLContextCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * M1 of the OpenCL worldgen port: prove the OpenCL pipeline works inside the
 * Plutonium/Forge process before investing in the density-function port.
 *
 * <p>Enumerates every OpenCL platform + device (so we can see the RTX 3080), then
 * compiles and runs a trivial vector-add kernel on the first GPU and verifies the
 * result. Pure LWJGL OpenCL — no native DLL of ours; OpenCL is loaded from the
 * system ICD (the GPU driver). This is the foundation that replaces the CUDA path.
 */
public final class OpenCLProbe {

    private static final Logger LOGGER = LogManager.getLogger("PlutoniumOpenCL");

    private static final String VADD_KERNEL = """
            kernel void vadd(global const float* a, global const float* b, global float* out) {
                int i = get_global_id(0);
                out[i] = a[i] + b[i];
            }
            """;

    private OpenCLProbe() {}

    /** Runs the full probe. Returns true if a GPU ran the kernel correctly. */
    public static boolean probe() {
        LOGGER.info("[Plutonium/OpenCL] Probing OpenCL (this replaces the CUDA native DLL path)...");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            int err = CL10.clGetPlatformIDs(null, pCount);
            if (err != CL10.CL_SUCCESS || pCount.get(0) == 0) {
                LOGGER.warn("[Plutonium/OpenCL] No OpenCL platforms found (err={}). Is an OpenCL ICD installed?", err);
                return false;
            }
            int platformCount = pCount.get(0);
            PointerBuffer platforms = stack.mallocPointer(platformCount);
            checkCL(CL10.clGetPlatformIDs(platforms, (IntBuffer) null));

            long chosenPlatform = NULL;
            long chosenDevice = NULL;
            String chosenName = null;

            for (int i = 0; i < platformCount; i++) {
                long platform = platforms.get(i);
                LOGGER.info("[Plutonium/OpenCL] Platform {}: {} | {} | {}", i,
                        platformInfo(platform, CL10.CL_PLATFORM_NAME),
                        platformInfo(platform, CL10.CL_PLATFORM_VENDOR),
                        platformInfo(platform, CL10.CL_PLATFORM_VERSION));

                IntBuffer dCount = stack.mallocInt(1);
                int derr = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_ALL, null, dCount);
                if (derr == CL10.CL_DEVICE_NOT_FOUND || dCount.get(0) == 0) {
                    continue;
                }
                checkCL(derr);
                int deviceCount = dCount.get(0);
                PointerBuffer devices = stack.mallocPointer(deviceCount);
                checkCL(CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_ALL, devices, (IntBuffer) null));

                for (int d = 0; d < deviceCount; d++) {
                    long device = devices.get(d);
                    long type = deviceInfoLong(device, CL10.CL_DEVICE_TYPE);
                    boolean isGpu = (type & CL10.CL_DEVICE_TYPE_GPU) != 0;
                    String name = deviceInfo(device, CL10.CL_DEVICE_NAME);
                    long globalMem = deviceInfoLong(device, CL10.CL_DEVICE_GLOBAL_MEM_SIZE);
                    long computeUnits = deviceInfoLong(device, CL10.CL_DEVICE_MAX_COMPUTE_UNITS);
                    LOGGER.info("[Plutonium/OpenCL]   Device {}: {} | {} | {} | {} CUs | {} MB | OpenCL {}",
                            d, name, deviceInfo(device, CL10.CL_DEVICE_VENDOR),
                            isGpu ? "GPU" : (type == CL10.CL_DEVICE_TYPE_CPU ? "CPU" : "OTHER"),
                            computeUnits, globalMem / (1024 * 1024),
                            deviceInfo(device, CL10.CL_DEVICE_VERSION));
                    if (isGpu && chosenDevice == NULL) {
                        chosenPlatform = platform;
                        chosenDevice = device;
                        chosenName = name;
                    }
                }
            }

            if (chosenDevice == NULL) {
                LOGGER.warn("[Plutonium/OpenCL] No GPU OpenCL device found — cannot accelerate worldgen on GPU.");
                return false;
            }

            LOGGER.info("[Plutonium/OpenCL] Running test kernel on: {}", chosenName);
            boolean ok = runVectorAdd(chosenPlatform, chosenDevice);
            if (ok) {
                LOGGER.info("[Plutonium/OpenCL] ✓ OpenCL pipeline WORKS on '{}'. Foundation ready for the worldgen port.", chosenName);
            } else {
                LOGGER.error("[Plutonium/OpenCL] ✗ Test kernel produced wrong results on '{}'.", chosenName);
            }
            return ok;
        } catch (Throwable t) {
            LOGGER.error("[Plutonium/OpenCL] Probe failed", t);
            return false;
        }
    }

    private static boolean runVectorAdd(long platform, long device) {
        final int n = 1024;
        long context = NULL, queue = NULL, program = NULL, kernel = NULL;
        long bufA = NULL, bufB = NULL, bufOut = NULL;
        CLContextCallback errCb = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            PointerBuffer ctxProps = stack.mallocPointer(3);
            ctxProps.put(CL10.CL_CONTEXT_PLATFORM).put(platform).put(0).flip();
            errCb = CLContextCallback.create((errinfo, private_info, cb, user_data) ->
                    LOGGER.error("[Plutonium/OpenCL] context error: {}", MemoryUtil.memUTF8(errinfo)));
            context = CL10.clCreateContext(ctxProps, device, errCb, NULL, errcode);
            checkCL(errcode);
            queue = CL10.clCreateCommandQueue(context, device, 0L, errcode);
            checkCL(errcode);

            program = CL10.clCreateProgramWithSource(context, VADD_KERNEL, errcode);
            checkCL(errcode);
            int build = CL10.clBuildProgram(program, device, "", null, NULL);
            if (build != CL10.CL_SUCCESS) {
                LOGGER.error("[Plutonium/OpenCL] kernel build failed: {}", buildLog(program, device));
                return false;
            }
            kernel = CL10.clCreateKernel(program, "vadd", errcode);
            checkCL(errcode);

            FloatBuffer a = MemoryUtil.memAllocFloat(n);
            FloatBuffer b = MemoryUtil.memAllocFloat(n);
            FloatBuffer out = MemoryUtil.memAllocFloat(n);
            try {
                for (int i = 0; i < n; i++) {
                    a.put(i, i);
                    b.put(i, i * 2.0f);
                }
                bufA = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR, a, errcode);
                checkCL(errcode);
                bufB = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR, b, errcode);
                checkCL(errcode);
                bufOut = CL10.clCreateBuffer(context, CL10.CL_MEM_WRITE_ONLY, (long) n * Float.BYTES, errcode);
                checkCL(errcode);

                checkCL(CL10.clSetKernelArg1p(kernel, 0, bufA));
                checkCL(CL10.clSetKernelArg1p(kernel, 1, bufB));
                checkCL(CL10.clSetKernelArg1p(kernel, 2, bufOut));

                PointerBuffer globalWorkSize = stack.mallocPointer(1).put(0, n);
                checkCL(CL10.clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, null, null));
                checkCL(CL10.clEnqueueReadBuffer(queue, bufOut, true, 0, out, null, null));
                checkCL(CL10.clFinish(queue));

                for (int i = 0; i < n; i++) {
                    float expected = i + i * 2.0f;
                    if (Math.abs(out.get(i) - expected) > 1e-4f) {
                        LOGGER.error("[Plutonium/OpenCL] mismatch at {}: got {} expected {}", i, out.get(i), expected);
                        return false;
                    }
                }
                return true;
            } finally {
                MemoryUtil.memFree(a);
                MemoryUtil.memFree(b);
                MemoryUtil.memFree(out);
            }
        } finally {
            if (bufA != NULL) CL10.clReleaseMemObject(bufA);
            if (bufB != NULL) CL10.clReleaseMemObject(bufB);
            if (bufOut != NULL) CL10.clReleaseMemObject(bufOut);
            if (kernel != NULL) CL10.clReleaseKernel(kernel);
            if (program != NULL) CL10.clReleaseProgram(program);
            if (queue != NULL) CL10.clReleaseCommandQueue(queue);
            if (context != NULL) CL10.clReleaseContext(context);
            if (errCb != null) errCb.free();
        }
    }

    private static String platformInfo(long platform, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            checkCL(CL10.clGetPlatformInfo(platform, param, (ByteBuffer) null, size));
            int bytes = (int) size.get(0);
            ByteBuffer buffer = stack.malloc(bytes);
            checkCL(CL10.clGetPlatformInfo(platform, param, buffer, null));
            return MemoryUtil.memUTF8(buffer, bytes - 1);
        }
    }

    private static String deviceInfo(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            checkCL(CL10.clGetDeviceInfo(device, param, (ByteBuffer) null, size));
            int bytes = (int) size.get(0);
            ByteBuffer buffer = stack.malloc(bytes);
            checkCL(CL10.clGetDeviceInfo(device, param, buffer, null));
            return MemoryUtil.memUTF8(buffer, bytes - 1);
        }
    }

    private static long deviceInfoLong(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer value = stack.mallocLong(1);
            checkCL(CL10.clGetDeviceInfo(device, param, value, null));
            return value.get(0);
        }
    }

    private static String buildLog(long program, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size);
            int bytes = (int) size.get(0);
            ByteBuffer buffer = stack.malloc(bytes);
            CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, buffer, null);
            return MemoryUtil.memUTF8(buffer, bytes - 1);
        }
    }

    private static void checkCL(int errcode) {
        if (errcode != CL10.CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + errcode);
        }
    }

    private static void checkCL(IntBuffer errcode) {
        checkCL(errcode.get(0));
    }
}
