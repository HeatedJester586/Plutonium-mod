//1. Windows System Headers (Always First)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN // This prevents90% of winnt.h errors
#endif
#include <windows.h>
#include <GL/gl.h> // OpenGL must come after windows.h
// This line tells Visual Studio to find the OpenGL library automatically
#pragma comment(lib, "opengl32.lib")

//2. Standard C++ Headers
#include <iostream>
#include <vector>
#include <memory>
#include <chrono>
#include <atomic>
#include <thread>
#include <mutex>
#include <cstring>

//3. JNI Headers
#include <jni.h>

//4. Graphics/Compute Headers (Last)
#include <cuda_runtime.h>
#include <device_launch_parameters.h>
#include <cstdarg>
#include <cstdio>
#include "ComputeEngine.h"
#include "plutonium_log_queue.h"

static inline void pluto_native_log_impl(const char* fmt, ...) {
    char body[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);
    fprintf(stdout, "[Plutonium/Native] %s\n", body);
    fflush(stdout);
    char queued[1100];
    snprintf(queued, sizeof(queued), "[Plutonium/Native] %s", body);
    pluto_log_queue_push(queued);
}

#define PLUTO_LOG(fmt, ...) pluto_native_log_impl(fmt, ##__VA_ARGS__)

static int s_deviceIndex =0;

struct CudaUVRect {
 float u0, v0, u1, v1;
};

static CudaUVRect* g_cudaUvTable = nullptr;
static int g_cudaUvTableCount = 0;
static std::mutex g_cudaUvTableMutex;

void plutoniumCudaUploadTextureTable(const void* data, int rectCount) {
 if (!data || rectCount <= 0) return;
 std::lock_guard<std::mutex> lock(g_cudaUvTableMutex);
 cudaSetDevice(s_deviceIndex);
 CudaUVRect* next = nullptr;
 size_t bytes = (size_t)rectCount * sizeof(CudaUVRect);
 cudaError_t err = cudaMalloc((void**)&next, bytes);
 if (err != cudaSuccess) {
  PLUTO_LOG("CUDA UV table alloc fail: %s", cudaGetErrorString(err));
  return;
 }
 err = cudaMemcpy(next, data, bytes, cudaMemcpyHostToDevice);
 if (err != cudaSuccess) {
  PLUTO_LOG("CUDA UV table upload fail: %s", cudaGetErrorString(err));
  cudaFree(next);
  return;
 }
 CudaUVRect* old = g_cudaUvTable;
 g_cudaUvTable = next;
 g_cudaUvTableCount = rectCount;
 if (old) cudaFree(old);
 PLUTO_LOG("CUDA UV table uploaded: %d entries (%zu bytes).", rectCount, bytes);
}

// --- GPU NOISE MATH (Fixed for CUDA) ---
__device__ float fractf(float x) { return x - floorf(x); }

__device__ float fade(float t) { 
	return t * t * t * (t * (t *6.0f -15.0f) +10.0f); 
}

__device__ float lerp(float t, float a, float b) { 
	return a + t * (b - a); 
}

__device__ float gpu_simplex_noise(float x, float z, long seed) {
	float x_s = x + (float)(seed %1000);
	float z_s = z + (float)((seed /1000) %1000);
	
	float ix = floorf(x_s);
	float iz = floorf(z_s);
	float fx = fractf(x_s);
	float fz = fractf(z_s);

	// Fade curves
	float ux = fade(fx);
	float uz = fade(fz);

	// Simple hashing for2D terrain
	auto hash = [](float x, float z) {
		return fractf(sinf(x *12.9898f + z *78.233f) *43758.5453f);
	};

	float res = lerp(uz, 
		lerp(ux, hash(ix, iz), hash(ix +1.0f, iz)),
		lerp(ux, hash(ix, iz +1.0f), hash(ix +1.0f, iz +1.0f))
	);

	return res;
}

// Fast GPU-friendly Hash
__device__ float gpu_rand(float x, float z) {
 return fractf(sinf(x *12.9898f + z *78.233f) *43758.5453f);
}

// ── Density AST virtual machine ──────────────────────────────────────────────
__device__ double plutoniumClamp(double value, double minValue, double maxValue) {
 return value < minValue ? minValue : (value > maxValue ? maxValue : value);
}

__device__ double plutoniumClampedMap(
 double value, double fromLow, double fromHigh, double toLow, double toHigh)
{
 if (fromHigh == fromLow) return value < fromLow ? toLow : toHigh;
 double t = plutoniumClamp((value - fromLow) / (fromHigh - fromLow), 0.0, 1.0);
 return toLow + (toHigh - toLow) * t;
}

__device__ float plutoniumLerp(float t, float a, float b) {
 return a + t * (b - a);
}

__device__ int plutoniumFindIntervalStart(const float* locations, int count, float x) {
 int low = 0;
 int high = count;
 while (low < high) {
  int mid = (low + high) >> 1;
  if (x < locations[mid]) {
   high = mid;
  } else {
   low = mid + 1;
  }
 }
 return low - 1;
}

__device__ double evaluateFlatSpline(const unsigned char* dataPool, int32_t offset, const double* regs) {
 const unsigned char* node = dataPool + offset;
 int32_t nodeType = *reinterpret_cast<const int32_t*>(node);

 if (nodeType == 0) {
  return (double)(*reinterpret_cast<const float*>(node + 4));
 }

 // Multipoint layout:
 // int32 nodeType, regIdx, count, locationsOffset, childOffsetsOffset,
 // derivativesOffset, reserved
 const int32_t* header = reinterpret_cast<const int32_t*>(node);
 int32_t regIdx = header[1];
 int32_t count = header[2];
 int32_t locationsOffset = header[3];
 int32_t childOffsetsOffset = header[4];
 int32_t derivativesOffset = header[5];

 if (count <= 0) return 0.0;

 const float* locations = reinterpret_cast<const float*>(dataPool + locationsOffset);
 const int32_t* childOffsets = reinterpret_cast<const int32_t*>(dataPool + childOffsetsOffset);
 const float* derivatives = reinterpret_cast<const float*>(dataPool + derivativesOffset);

 float x = (float)regs[regIdx];
 int last = count - 1;
 int idx = plutoniumFindIntervalStart(locations, count, x);

 if (idx < 0) {
  return (double)((float)evaluateFlatSpline(dataPool, childOffsets[0], regs)
   + derivatives[0] * (x - locations[0]));
 }
 if (idx == last) {
  return (double)((float)evaluateFlatSpline(dataPool, childOffsets[last], regs)
   + derivatives[last] * (x - locations[last]));
 }

 float xLow = locations[idx];
 float xHigh = locations[idx + 1];
 float k = (x - xLow) / (xHigh - xLow);

 float yLow = (float)evaluateFlatSpline(dataPool, childOffsets[idx], regs);
 float yHigh = (float)evaluateFlatSpline(dataPool, childOffsets[idx + 1], regs);

 float dLow = derivatives[idx];
 float dHigh = derivatives[idx + 1];

 float a = dLow * (xHigh - xLow) - (yHigh - yLow);
 float b = -dHigh * (xHigh - xLow) + (yHigh - yLow);

 return (double)(plutoniumLerp(k, yLow, yHigh)
  + k * (1.0f - k) * plutoniumLerp(k, a, b));
}

__device__ double evaluateWeirdScaledSamplerRarity(const unsigned char* dataPool, int32_t offset, double value) {
 const int32_t* payload = reinterpret_cast<const int32_t*>(dataPool + offset);
 int32_t mapperId = payload[0];
 if (mapperId == 1) {
  return value < -0.75 ? 0.5 : value < -0.5 ? 0.75 : value < 0.5 ? 1.0 : value < 0.75 ? 2.0 : 3.0;
 }
 return value < -0.5 ? 0.75 : value < 0.0 ? 1.0 : value < 0.5 ? 1.5 : 2.0;
}

__device__ double plutoniumFade(double t) {
 return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

__device__ double plutoniumLerpD(double t, double a, double b) {
 return a + t * (b - a);
}

__device__ double plutoniumLerp2D(double tx, double ty, double v00, double v10, double v01, double v11) {
 return plutoniumLerpD(ty, plutoniumLerpD(tx, v00, v10), plutoniumLerpD(tx, v01, v11));
}

__device__ double plutoniumLerp3D(
 double tx, double ty, double tz,
 double v000, double v100, double v010, double v110,
 double v001, double v101, double v011, double v111)
{
 return plutoniumLerpD(
  tz,
  plutoniumLerp2D(tx, ty, v000, v100, v010, v110),
  plutoniumLerp2D(tx, ty, v001, v101, v011, v111));
}

__device__ int plutoniumFloor(double value) {
 int i = (int)value;
 return value < (double)i ? i - 1 : i;
}

__device__ double plutoniumWrap(double value) {
 return value - floor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
}

__device__ int improved_p(const uint8_t* p_table, int value) {
 return (int)p_table[value & 255];
}

__device__ double improved_grad_dot(int hash, double x, double y, double z) {
 switch (hash & 15) {
  case 0:  return  x + y;
  case 1:  return -x + y;
  case 2:  return  x - y;
  case 3:  return -x - y;
  case 4:  return  x + z;
  case 5:  return -x + z;
  case 6:  return  x - z;
  case 7:  return -x - z;
  case 8:  return  y + z;
  case 9:  return -y + z;
  case 10: return  y - z;
  case 11: return -y - z;
  case 12: return  x + y;
  case 13: return -y + z;
  case 14: return -x + y;
  default: return -y - z;
 }
}

__device__ double improved_noise(double x, double y, double z, const uint8_t* p_table) {
 int i = plutoniumFloor(x);
 int j = plutoniumFloor(y);
 int k = plutoniumFloor(z);
 double dx = x - (double)i;
 double dy = y - (double)j;
 double dz = z - (double)k;

 int px0 = improved_p(p_table, i);
 int px1 = improved_p(p_table, i + 1);
 int pxy00 = improved_p(p_table, px0 + j);
 int pxy01 = improved_p(p_table, px0 + j + 1);
 int pxy10 = improved_p(p_table, px1 + j);
 int pxy11 = improved_p(p_table, px1 + j + 1);

 double d0 = improved_grad_dot(improved_p(p_table, pxy00 + k),     dx,       dy,       dz);
 double d1 = improved_grad_dot(improved_p(p_table, pxy10 + k),     dx - 1.0, dy,       dz);
 double d2 = improved_grad_dot(improved_p(p_table, pxy01 + k),     dx,       dy - 1.0, dz);
 double d3 = improved_grad_dot(improved_p(p_table, pxy11 + k),     dx - 1.0, dy - 1.0, dz);
 double d4 = improved_grad_dot(improved_p(p_table, pxy00 + k + 1), dx,       dy,       dz - 1.0);
 double d5 = improved_grad_dot(improved_p(p_table, pxy10 + k + 1), dx - 1.0, dy,       dz - 1.0);
 double d6 = improved_grad_dot(improved_p(p_table, pxy01 + k + 1), dx,       dy - 1.0, dz - 1.0);
 double d7 = improved_grad_dot(improved_p(p_table, pxy11 + k + 1), dx - 1.0, dy - 1.0, dz - 1.0);

 return plutoniumLerp3D(plutoniumFade(dx), plutoniumFade(dy), plutoniumFade(dz), d0, d1, d2, d3, d4, d5, d6, d7);
}

__device__ const unsigned char* octave_payload(const unsigned char* octaveBase, int32_t index) {
 return octaveBase + index * 288;
}

// ── BlendedNoise primitives ─────────────────────────────────────────────────
// Mirrors the deprecated 5-arg ImprovedNoise.noise(x,y,z,smearY,freqY) from vanilla
// 1.20.1 (net.minecraft.world.level.levelgen.synth.ImprovedNoise). The "smear"
// quantizes the dy component going into gradient dot products, but smoothstep
// blending still uses the ORIGINAL dy. That asymmetry is what gives BlendedNoise
// its terrain look — don't simplify it away.
__device__ double improved_noise_smeared(
 double x, double y, double z, double smearY, double freqY,
 double xo, double yo, double zo, const uint8_t* p_table)
{
 double d0 = x + xo;
 double d1 = y + yo;
 double d2 = z + zo;
 int i = plutoniumFloor(d0);
 int j = plutoniumFloor(d1);
 int k = plutoniumFloor(d2);
 double dx = d0 - (double)i;
 double dy = d1 - (double)j;
 double dz = d2 - (double)k;

 double d6;
 if (smearY != 0.0) {
  double d7 = (freqY >= 0.0 && freqY < dy) ? freqY : dy;
  d6 = (double)plutoniumFloor(d7 / smearY + 1.0e-7) * smearY;
 } else {
  d6 = 0.0;
 }
 double dyMod = dy - d6;

 int px0 = improved_p(p_table, i);
 int px1 = improved_p(p_table, i + 1);
 int pxy00 = improved_p(p_table, px0 + j);
 int pxy01 = improved_p(p_table, px0 + j + 1);
 int pxy10 = improved_p(p_table, px1 + j);
 int pxy11 = improved_p(p_table, px1 + j + 1);

 double g0 = improved_grad_dot(improved_p(p_table, pxy00 + k),     dx,       dyMod,       dz);
 double g1 = improved_grad_dot(improved_p(p_table, pxy10 + k),     dx - 1.0, dyMod,       dz);
 double g2 = improved_grad_dot(improved_p(p_table, pxy01 + k),     dx,       dyMod - 1.0, dz);
 double g3 = improved_grad_dot(improved_p(p_table, pxy11 + k),     dx - 1.0, dyMod - 1.0, dz);
 double g4 = improved_grad_dot(improved_p(p_table, pxy00 + k + 1), dx,       dyMod,       dz - 1.0);
 double g5 = improved_grad_dot(improved_p(p_table, pxy10 + k + 1), dx - 1.0, dyMod,       dz - 1.0);
 double g6 = improved_grad_dot(improved_p(p_table, pxy01 + k + 1), dx,       dyMod - 1.0, dz - 1.0);
 double g7 = improved_grad_dot(improved_p(p_table, pxy11 + k + 1), dx - 1.0, dyMod - 1.0, dz - 1.0);

 // smoothstep weights use ORIGINAL dy (not dyMod) — this is the BlendedNoise quirk.
 return plutoniumLerp3D(
  plutoniumFade(dx), plutoniumFade(dy), plutoniumFade(dz),
  g0, g1, g2, g3, g4, g5, g6, g7);
}

// Vanilla BlendedNoise.compute — 1.20.1 byte-exact.
// Payload layout written by Java's BytecodeCompiler.writeBlendedNoise:
//   double xzMul, yMul, xzFactor, yFactor, smearMult            (40 B)
//   int32  minCount, maxCount, mainCount, _pad                  (16 B)
//   OctaveNoisePayload[minCount]   (each 288 B: i32 present, _pad, dbl xo, yo, zo, byte perm[256])
//   OctaveNoisePayload[maxCount]
//   OctaveNoisePayload[mainCount]
// Vanilla's PerlinNoise.getOctaveNoise(i) returns noiseLevels[len-1-i], so we
// walk each octave array in reverse — the `octIdx = count - 1 - i` lines below.
__device__ double evaluateBlendedNoise(
 const unsigned char* payload, double worldX, double worldY, double worldZ)
{
 const double* params = reinterpret_cast<const double*>(payload);
 double xzMul    = params[0];
 double yMul     = params[1];
 double xzFactor = params[2];
 double yFactor  = params[3];
 double smearMul = params[4];

 const int32_t* counts = reinterpret_cast<const int32_t*>(payload + 40);
 int32_t minCount  = counts[0];
 int32_t maxCount  = counts[1];
 int32_t mainCount = counts[2];

 const unsigned char* minOcts  = payload + 56;
 const unsigned char* maxOcts  = minOcts + minCount  * 288;
 const unsigned char* mainOcts = maxOcts + maxCount  * 288;

 double d0 = worldX * xzMul;
 double d1 = worldY * yMul;
 double d2 = worldZ * xzMul;
 double d3 = d0 / xzFactor;
 double d4 = d1 / yFactor;
 double d5 = d2 / xzFactor;
 double d6 = yMul * smearMul;
 double d7 = d6 / yFactor;

 // ── main noise: 8 octaves drive the 0..1 lerp parameter
 double d10 = 0.0;
 double d11 = 1.0;
 for (int i = 0; i < mainCount; ++i) {
  int octIdx = mainCount - 1 - i;
  const unsigned char* oct = mainOcts + octIdx * 288;
  int32_t present = *reinterpret_cast<const int32_t*>(oct);
  if (present) {
   const double* off = reinterpret_cast<const double*>(oct + 8);
   const uint8_t* p  = reinterpret_cast<const uint8_t*>(oct + 32);
   d10 += improved_noise_smeared(
    plutoniumWrap(d3 * d11),
    plutoniumWrap(d4 * d11),
    plutoniumWrap(d5 * d11),
    d7 * d11,
    d4 * d11,
    off[0], off[1], off[2], p) / d11;
  }
  d11 *= 0.5;
 }

 double d16 = (d10 / 10.0 + 1.0) / 2.0;
 bool clampHigh = d16 >= 1.0;
 bool clampLow  = d16 <= 0.0;

 // ── limit noises: 16 octaves each, skipped when clamped to one side
 double d8 = 0.0;
 double d9 = 0.0;
 d11 = 1.0;
 for (int j = 0; j < minCount; ++j) {
  double d12 = plutoniumWrap(d0 * d11);
  double d13 = plutoniumWrap(d1 * d11);
  double d14 = plutoniumWrap(d2 * d11);
  double d15 = d6 * d11;
  double d1m = d1 * d11;
  int octIdx = minCount - 1 - j;

  if (!clampHigh) {
   const unsigned char* oct = minOcts + octIdx * 288;
   int32_t present = *reinterpret_cast<const int32_t*>(oct);
   if (present) {
    const double* off = reinterpret_cast<const double*>(oct + 8);
    const uint8_t* p  = reinterpret_cast<const uint8_t*>(oct + 32);
    d8 += improved_noise_smeared(d12, d13, d14, d15, d1m,
                                 off[0], off[1], off[2], p) / d11;
   }
  }
  if (!clampLow) {
   const unsigned char* oct = maxOcts + octIdx * 288;
   int32_t present = *reinterpret_cast<const int32_t*>(oct);
   if (present) {
    const double* off = reinterpret_cast<const double*>(oct + 8);
    const uint8_t* p  = reinterpret_cast<const uint8_t*>(oct + 32);
    d9 += improved_noise_smeared(d12, d13, d14, d15, d1m,
                                 off[0], off[1], off[2], p) / d11;
   }
  }
  d11 *= 0.5;
 }

 // Mth.clampedLerp(min/512, max/512, d16) / 128
 double t = d16 < 0.0 ? 0.0 : d16 > 1.0 ? 1.0 : d16;
 double minV = d8 / 512.0;
 double maxV = d9 / 512.0;
 return (minV + t * (maxV - minV)) / 128.0;
}

__device__ double evaluatePerlinPayload(
 const unsigned char* octaveBase,
 int32_t firstOctave, const double* amplitudes, int32_t amplitudeCount,
 double x, double y, double z)
{
 if (amplitudeCount <= 0) return 0.0;

 double frequency = exp2((double)firstOctave);
 double valueFactor = exp2((double)(amplitudeCount - 1)) / (exp2((double)amplitudeCount) - 1.0);
 double sum = 0.0;

 for (int i = 0; i < amplitudeCount; ++i) {
  const unsigned char* octave = octave_payload(octaveBase, i);
  int32_t present = *reinterpret_cast<const int32_t*>(octave);
  if (present != 0) {
   const double* offsets = reinterpret_cast<const double*>(octave + 8);
   const uint8_t* p = reinterpret_cast<const uint8_t*>(octave + 32);
   sum += amplitudes[i] * improved_noise(
    plutoniumWrap(x * frequency) + offsets[0],
    plutoniumWrap(y * frequency) + offsets[1],
    plutoniumWrap(z * frequency) + offsets[2],
    p) * valueFactor;
  }
  frequency *= 2.0;
  valueFactor /= 2.0;
 }

 return sum;
}

__device__ double evaluateNormalNoisePayloadRaw(
 const unsigned char* payload, double x, double y, double z)
{
 const int32_t* header = reinterpret_cast<const int32_t*>(payload);
 int32_t firstOctave = header[0];
 int32_t amplitudeCount = header[1];
 const double* amplitudes = reinterpret_cast<const double*>(payload + 8);
 const double* scalars = reinterpret_cast<const double*>(payload + 8 + amplitudeCount * (int32_t)sizeof(double));
 double valueFactor = scalars[2];
 const unsigned char* firstOctaves = payload + 8 + amplitudeCount * (int32_t)sizeof(double) + 24;
 const unsigned char* secondOctaves = firstOctaves + amplitudeCount * 288;

 const double inputFactor = 1.0181268882175227;
 return (
  evaluatePerlinPayload(firstOctaves, firstOctave, amplitudes, amplitudeCount, x, y, z) +
  evaluatePerlinPayload(secondOctaves, firstOctave, amplitudes, amplitudeCount, x * inputFactor, y * inputFactor, z * inputFactor)
 ) * valueFactor;
}

__device__ double evaluateNoisePayload(
 const unsigned char* dataPool, int32_t dataOffset, double x, double y, double z)
{
 const unsigned char* payload = dataPool + dataOffset;
 const int32_t* header = reinterpret_cast<const int32_t*>(payload);
 int32_t amplitudeCount = header[1];
 const double* scales = reinterpret_cast<const double*>(payload + 8 + amplitudeCount * (int32_t)sizeof(double));

 double xzScale = scales[0];
 double yScale = scales[1];
 double nx = (xzScale != 0.0) ? x * xzScale : x;
 double ny = (yScale != 0.0) ? y * yScale : y;
 double nz = (xzScale != 0.0) ? z * xzScale : z;

 return evaluateNormalNoisePayloadRaw(payload, nx, ny, nz);
}

__device__ double evaluateShiftedNoisePayload(
 const unsigned char* dataPool, int32_t dataOffset,
 double x, double y, double z, const double* regs)
{
 const unsigned char* payload = dataPool + dataOffset;
 const int32_t* header = reinterpret_cast<const int32_t*>(payload);
 int32_t amplitudeCount = header[1];
 int normalPayloadBytes = 8 + amplitudeCount * (int32_t)sizeof(double) + 24 + amplitudeCount * 288 * 2;
 int scaleOffset = 8 + amplitudeCount * (int32_t)sizeof(double);
 const double* scales = reinterpret_cast<const double*>(payload + scaleOffset);
 const int32_t* shifts = reinterpret_cast<const int32_t*>(payload + normalPayloadBytes);

 double dx = x * scales[0] + regs[shifts[0]];
 double dy = y * scales[1] + regs[shifts[1]];
 double dz = z * scales[0] + regs[shifts[2]];

 return evaluateNormalNoisePayloadRaw(payload, dx, dy, dz);
}

__device__ double evaluateWeirdScaledSamplerPayload(
 const unsigned char* dataPool, int32_t dataOffset,
 double rarityInput, double worldX, double worldY, double worldZ)
{
 const unsigned char* payload = dataPool + dataOffset;
 int32_t mapperId = *reinterpret_cast<const int32_t*>(payload);
 double scale = mapperId == 1
  ? (rarityInput < -0.75 ? 0.5 : rarityInput < -0.5 ? 0.75 : rarityInput < 0.5 ? 1.0 : rarityInput < 0.75 ? 2.0 : 3.0)
  : (rarityInput < -0.5 ? 0.75 : rarityInput < 0.0 ? 1.0 : rarityInput < 0.5 ? 1.5 : 2.0);
 if (scale == 0.0) return 0.0;
 const unsigned char* normalPayload = payload + 8;
 return scale * fabs(evaluateNormalNoisePayloadRaw(
  normalPayload, worldX / scale, worldY / scale, worldZ / scale));
}

constexpr int PLUTONIUM_MAX_AST_INSTRUCTIONS = 8192;
constexpr int PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK = 64;
constexpr int PLUTONIUM_DENSITY_CELL_WIDTH = 4;
constexpr int PLUTONIUM_DENSITY_CELL_HEIGHT = 8;
constexpr int PLUTONIUM_DENSITY_CELLS_X = 16 / PLUTONIUM_DENSITY_CELL_WIDTH;
constexpr int PLUTONIUM_DENSITY_CELLS_Z = 16 / PLUTONIUM_DENSITY_CELL_WIDTH;
constexpr int PLUTONIUM_DENSITY_CELLS_Y = 384 / PLUTONIUM_DENSITY_CELL_HEIGHT;
constexpr int PLUTONIUM_DENSITY_GRID_X = PLUTONIUM_DENSITY_CELLS_X + 1;
constexpr int PLUTONIUM_DENSITY_GRID_Z = PLUTONIUM_DENSITY_CELLS_Z + 1;
constexpr int PLUTONIUM_DENSITY_GRID_Y = PLUTONIUM_DENSITY_CELLS_Y + 1;
constexpr int PLUTONIUM_DENSITY_CELL_COUNT =
 PLUTONIUM_DENSITY_GRID_X * PLUTONIUM_DENSITY_GRID_Y * PLUTONIUM_DENSITY_GRID_Z;

__device__ double evaluateAST(int worldX, int worldY, int worldZ, const void* astBuffer, double* regs) {
 if (!astBuffer || !regs) return 0.0;

 const unsigned char* base = static_cast<const unsigned char*>(astBuffer);
 const PlutoniumBytecodeHeader* header =
  reinterpret_cast<const PlutoniumBytecodeHeader*>(base);

 if (header->magic != 0x504C544E || header->version != 1 ||
     header->instructionStride != sizeof(PlutoniumInstruction) ||
     header->instructionCount <= 0 || header->instructionCount > PLUTONIUM_MAX_AST_INSTRUCTIONS) {
  return 0.0;
 }

 const PlutoniumInstruction* instructions =
  reinterpret_cast<const PlutoniumInstruction*>(base + sizeof(PlutoniumBytecodeHeader));
 const unsigned char* dataPool = base + header->dataPoolOffset;

 for (int i = 0; i < header->instructionCount; ++i) {
  const PlutoniumInstruction& ins = instructions[i];
  switch (static_cast<PlutoniumOpcode>(ins.opcodeId)) {
   case PlutoniumOpcode::CONSTANT:
    regs[i] = ins.value;
    break;
   case PlutoniumOpcode::ADD:
    regs[i] = regs[ins.arg1] + regs[ins.arg2];
    break;
   case PlutoniumOpcode::MUL:
    regs[i] = regs[ins.arg1] * regs[ins.arg2];
    break;
   case PlutoniumOpcode::MIN:
    regs[i] = fmin(regs[ins.arg1], regs[ins.arg2]);
    break;
   case PlutoniumOpcode::MAX:
    regs[i] = fmax(regs[ins.arg1], regs[ins.arg2]);
    break;
   case PlutoniumOpcode::ABS:
    regs[i] = fabs(regs[ins.arg1]);
    break;
   case PlutoniumOpcode::SQUARE: {
    double v = regs[ins.arg1];
    regs[i] = v * v;
    break;
   }
   case PlutoniumOpcode::CUBE: {
    double v = regs[ins.arg1];
    regs[i] = v * v * v;
    break;
   }
   case PlutoniumOpcode::HALF_NEGATIVE: {
    double v = regs[ins.arg1];
    regs[i] = v > 0.0 ? v : v * 0.5;
    break;
   }
   case PlutoniumOpcode::QUARTER_NEGATIVE: {
    double v = regs[ins.arg1];
    regs[i] = v > 0.0 ? v : v * 0.25;
    break;
   }
   case PlutoniumOpcode::SQUEEZE: {
    double v = plutoniumClamp(regs[ins.arg1], -1.0, 1.0);
    regs[i] = v / 2.0 - (v * v * v) / 24.0;
    break;
   }
   case PlutoniumOpcode::MARKER:
    regs[i] = regs[ins.arg1];
    break;
   case PlutoniumOpcode::CLAMP: {
    const double* bounds = reinterpret_cast<const double*>(dataPool + ins.dataOffset);
    regs[i] = plutoniumClamp(regs[ins.arg1], bounds[0], bounds[1]);
    break;
   }
   case PlutoniumOpcode::BLEND_ALPHA:
    regs[i] = 1.0;
    break;
   case PlutoniumOpcode::BLEND_OFFSET:
   case PlutoniumOpcode::BEARDIFIER_MARKER:
    regs[i] = 0.0;
    break;
   case PlutoniumOpcode::BLEND_DENSITY:
    regs[i] = regs[ins.arg1];
    break;
   case PlutoniumOpcode::RANGE_CHOICE: {
    const int32_t* branch = reinterpret_cast<const int32_t*>(dataPool + ins.dataOffset);
    const double* range = reinterpret_cast<const double*>(dataPool + ins.dataOffset + 8);
    double d = regs[ins.arg1];
    regs[i] = (d >= range[0] && d < range[1]) ? regs[branch[0]] : regs[branch[1]];
    break;
   }
   case PlutoniumOpcode::Y_CLAMPED_GRADIENT: {
    const int32_t* yRange = reinterpret_cast<const int32_t*>(dataPool + ins.dataOffset);
    const double* values = reinterpret_cast<const double*>(dataPool + ins.dataOffset + 8);
    regs[i] = plutoniumClampedMap(
     (double)worldY, (double)yRange[0], (double)yRange[1], values[0], values[1]);
    break;
   }
   case PlutoniumOpcode::MUL_OR_ADD:
    regs[i] = (ins.arg2 == 0) ? regs[ins.arg1] * ins.value : regs[ins.arg1] + ins.value;
    break;
   case PlutoniumOpcode::NOISE:
    regs[i] = evaluateNoisePayload(dataPool, ins.dataOffset, (double)worldX, (double)worldY, (double)worldZ);
    break;
   case PlutoniumOpcode::SHIFT:
    regs[i] = evaluateNoisePayload(
     dataPool, ins.dataOffset,
     (double)worldX * 0.25, (double)worldY * 0.25, (double)worldZ * 0.25) * 4.0;
    break;
   case PlutoniumOpcode::SHIFT_A:
    regs[i] = evaluateNoisePayload(
     dataPool, ins.dataOffset,
     (double)worldX * 0.25, 0.0, (double)worldZ * 0.25) * 4.0;
    break;
   case PlutoniumOpcode::SHIFT_B:
    regs[i] = evaluateNoisePayload(
     dataPool, ins.dataOffset,
     (double)worldZ * 0.25, (double)worldX * 0.25, 0.0) * 4.0;
    break;
   case PlutoniumOpcode::SHIFTED_NOISE:
    regs[i] = evaluateShiftedNoisePayload(
     dataPool, ins.dataOffset, (double)worldX, (double)worldY, (double)worldZ, regs);
    break;
   case PlutoniumOpcode::BLENDED_NOISE:
    regs[i] = evaluateBlendedNoise(dataPool + ins.dataOffset,
                                   (double)worldX, (double)worldY, (double)worldZ);
    break;
   case PlutoniumOpcode::SPLINE:
    regs[i] = evaluateFlatSpline(dataPool, ins.dataOffset, regs);
   break;
   case PlutoniumOpcode::WEIRD_SCALED_SAMPLER: {
    regs[i] = evaluateWeirdScaledSamplerPayload(
     dataPool, ins.dataOffset, regs[ins.arg1],
     (double)worldX, (double)worldY, (double)worldZ);
    break;
   }
   default:
    regs[i] = 0.0;
    break;
  }
 }

 return regs[header->instructionCount - 1];
}

// ── Noise kernel ─────────────────────────────────────────────────────────────
__device__ int densityCellIndex(int cx, int cy, int cz) {
 return (cy * PLUTONIUM_DENSITY_GRID_Z + cz) * PLUTONIUM_DENSITY_GRID_X + cx;
}

__global__ void evaluateDensityCellsKernel(
 double* densityCells, int chunkX, int chunkZ, long seed, const void* astBuffer, double* astRegisters)
{
 int idx = blockIdx.x * blockDim.x + threadIdx.x;
 if (idx >= PLUTONIUM_DENSITY_CELL_COUNT) return;
 (void)seed;

 int cx = idx % PLUTONIUM_DENSITY_GRID_X;
 int t = idx / PLUTONIUM_DENSITY_GRID_X;
 int cz = t % PLUTONIUM_DENSITY_GRID_Z;
 int cy = t / PLUTONIUM_DENSITY_GRID_Z;

 int worldX = chunkX * 16 + cx * PLUTONIUM_DENSITY_CELL_WIDTH;
 int worldY = -64 + cy * PLUTONIUM_DENSITY_CELL_HEIGHT;
 int worldZ = chunkZ * 16 + cz * PLUTONIUM_DENSITY_CELL_WIDTH;
 double* regs = astRegisters ? astRegisters + ((size_t)idx * PLUTONIUM_MAX_AST_INSTRUCTIONS) : nullptr;

 densityCells[idx] = astBuffer ? evaluateAST(worldX, worldY, worldZ, astBuffer, regs) : 0.0;
}

__global__ void evaluateDensityPointsKernel(
 const int32_t* coordsXYZ, double* outValues, int count, const void* astBuffer, double* astRegisters)
{
 int idx = blockIdx.x * blockDim.x + threadIdx.x;
 if (idx >= count) return;
 double* regs = astRegisters ? astRegisters + ((size_t)idx * PLUTONIUM_MAX_AST_INSTRUCTIONS) : nullptr;
 int base = idx * 3;
 int worldX = coordsXYZ[base];
 int worldY = coordsXYZ[base + 1];
 int worldZ = coordsXYZ[base + 2];
 outValues[idx] = astBuffer ? evaluateAST(worldX, worldY, worldZ, astBuffer, regs) : 0.0;
}

__global__ void fillChunkFromDensityCellsKernel(VoxelBlock* chunk, const double* densityCells) {
 int idx = blockIdx.x * blockDim.x + threadIdx.x;
 if (idx >= CHUNK_VOLUME) return;

 int x = idx & 15;
 int z = (idx >> 4) & 15;
 int y = (idx >> 8);

 int cx = x / PLUTONIUM_DENSITY_CELL_WIDTH;
 int cz = z / PLUTONIUM_DENSITY_CELL_WIDTH;
 int cy = y / PLUTONIUM_DENSITY_CELL_HEIGHT;
 double tx = (double)(x - cx * PLUTONIUM_DENSITY_CELL_WIDTH) / (double)PLUTONIUM_DENSITY_CELL_WIDTH;
 double tz = (double)(z - cz * PLUTONIUM_DENSITY_CELL_WIDTH) / (double)PLUTONIUM_DENSITY_CELL_WIDTH;
 double ty = (double)(y - cy * PLUTONIUM_DENSITY_CELL_HEIGHT) / (double)PLUTONIUM_DENSITY_CELL_HEIGHT;

 double d000 = densityCells[densityCellIndex(cx,     cy,     cz)];
 double d100 = densityCells[densityCellIndex(cx + 1, cy,     cz)];
 double d001 = densityCells[densityCellIndex(cx,     cy,     cz + 1)];
 double d101 = densityCells[densityCellIndex(cx + 1, cy,     cz + 1)];
 double d010 = densityCells[densityCellIndex(cx,     cy + 1, cz)];
 double d110 = densityCells[densityCellIndex(cx + 1, cy + 1, cz)];
 double d011 = densityCells[densityCellIndex(cx,     cy + 1, cz + 1)];
 double d111 = densityCells[densityCellIndex(cx + 1, cy + 1, cz + 1)];

 double x00 = plutoniumLerpD(tx, d000, d100);
 double x01 = plutoniumLerpD(tx, d001, d101);
 double x10 = plutoniumLerpD(tx, d010, d110);
 double x11 = plutoniumLerpD(tx, d011, d111);
 double z0 = plutoniumLerpD(tz, x00, x01);
 double z1 = plutoniumLerpD(tz, x10, x11);
 double density = plutoniumLerpD(ty, z0, z1);

 unsigned char block = density > 0.0 ? 1 : 0;
 chunk[idx].blockID = block;
 chunk[idx].lightLevel = block ? 0 : 15;
 chunk[idx].meta = 0;
}

// ── Physics kernel ────────────────────────────────────────────────────────────
__global__ void physicsStep(VoxelBlock* world, VoxelBlock* out, int width, int height) {
    int x = blockIdx.x * blockDim.x + threadIdx.x;
    int y = blockIdx.y * blockDim.y + threadIdx.y;
    (void)y; // Suppress unused variable warning
    if (x >= width || y >= height) return;

    int idx = y * width + x;
    out[idx] = world[idx];

    if (world[idx].blockID != 0 && y + 1 < height) {
        int below = (y + 1) * width + x;
        if (world[below].blockID == 0) {
            out[idx].blockID = 0;
            out[below].blockID = world[idx].blockID;
            out[below].lightLevel = world[idx].lightLevel;
            out[below].meta = world[idx].meta;
        }
    }
}

// ── ComputeEngine ─────────────────────────────────────────────────────────────
ComputeEngine::ComputeEngine()
 : d_pinned_world(nullptr)
 , d_front(nullptr)
 , d_back(nullptr)
 , running(false)
 , cpuPool(nullptr)
 , d_chunk_buffer(nullptr)
 , h_chunk_buffer(nullptr)
 , d_densityCells(nullptr)
 , d_astRegisterBuffer(nullptr)
 , d_meshBlocks(nullptr)
 , d_meshVerts(nullptr)
 , d_meshVertCount(nullptr)
 , meshKernelCompleteEvent(nullptr) {
}

ComputeEngine::~ComputeEngine() {
 stop();
 if (cpuPool) { 
 delete cpuPool; 
 cpuPool = nullptr; 
 }
 
 // Cleanup pools (improvement 5)
 cleanupMeshPool();
 cleanupBlockBatchPool();

 // Free the device chunk generation context pool.
 {
  std::lock_guard<std::mutex> lock(chunkGenPoolMutex);
  for (auto& ctx : chunkGenPool) {
   if (ctx.stream)        cudaStreamDestroy(ctx.stream);
   if (ctx.d_chunk)       cudaFree(ctx.d_chunk);
   if (ctx.d_densityCells) cudaFree(ctx.d_densityCells);
   if (ctx.d_astRegisters) cudaFree(ctx.d_astRegisters);
  }
  chunkGenPool.clear();
 }
 
 // Cleanup CUDA event
 if (meshKernelCompleteEvent) {
  cudaEventDestroy(meshKernelCompleteEvent);
  meshKernelCompleteEvent = nullptr;
 }
 
 if (d_front) cudaFree(d_front);
 if (d_back) cudaFree(d_back);
 if (d_pinned_world) cudaFreeHost(d_pinned_world);
 if (d_chunk_buffer) cudaFree(d_chunk_buffer);
 if (h_chunk_buffer) cudaFreeHost(h_chunk_buffer);
 if (d_densityCells) { cudaFree(d_densityCells); d_densityCells = nullptr; }
 if (d_astBuffer) { cudaFree(d_astBuffer); d_astBuffer = nullptr; }
 if (d_astRegisterBuffer) { cudaFree(d_astRegisterBuffer); d_astRegisterBuffer = nullptr; }
 if (d_meshBlocks) { cudaFree(d_meshBlocks); d_meshBlocks = nullptr; }
 if (d_meshVerts) { cudaFree(d_meshVerts); d_meshVerts = nullptr; }
 if (d_meshVertCount) { cudaFree(d_meshVertCount); d_meshVertCount = nullptr; }
 {
  std::lock_guard<std::mutex> lock(g_cudaUvTableMutex);
  if (g_cudaUvTable) {
   cudaFree(g_cudaUvTable);
   g_cudaUvTable = nullptr;
   g_cudaUvTableCount = 0;
  }
 }

 // Free the device mesh context pool.
 {
  std::lock_guard<std::mutex> lock(deviceMeshPoolMutex);
  for (auto& ctx : deviceMeshPool) {
   if (ctx.d_blocks)    cudaFree(ctx.d_blocks);
   if (ctx.d_verts)     cudaFree(ctx.d_verts);
   if (ctx.d_vertCount) cudaFree(ctx.d_vertCount);
  }
  deviceMeshPool.clear();
 }

 PLUTO_LOG("ComputeEngine destroyed, all buffers freed.");
}

bool ComputeEngine::init(int deviceIndex, int width, int height, int threadCount) {
 s_deviceIndex = deviceIndex;
 cudaSetDevice(s_deviceIndex);
 // Add this line to prevent spin-locking the CPU core
 cudaSetDeviceFlags(cudaDeviceScheduleYield);
 // Create CUDA stream for async operations
 cudaStreamCreate(&m_stream);
 this->streamWidth = width;
 this->streamHeight = height;
 PLUTO_LOG("Initializing CUDA on device %d...", deviceIndex);

 cudaError_t err;

 size_t worldBytes = (size_t)streamWidth * streamHeight * sizeof(VoxelBlock);

 err = cudaMallocHost((void**)&d_pinned_world, worldBytes);
 if (err != cudaSuccess) {
 PLUTO_LOG("cudaMallocHost failed: %s", cudaGetErrorString(err));
 return false;
 }

 err = cudaMalloc((void**)&d_front, worldBytes);
 if (err != cudaSuccess) return false;

 err = cudaMalloc((void**)&d_back, worldBytes);
 if (err != cudaSuccess) return false;

 // Allocate chunk buffers
 size_t chunkBytes = (size_t)CHUNK_VOLUME * sizeof(VoxelBlock);
 err = cudaMalloc((void**)&d_chunk_buffer, chunkBytes);
 if (err != cudaSuccess) {
 PLUTO_LOG("cudaMalloc d_chunk_buffer failed: %s", cudaGetErrorString(err));
 return false;
 }
 err = cudaMallocHost((void**)&h_chunk_buffer, chunkBytes);
 if (err != cudaSuccess) {
 PLUTO_LOG("cudaMallocHost h_chunk_buffer failed: %s", cudaGetErrorString(err));
 return false;
 }

 const size_t densityBytes = (size_t)PLUTONIUM_DENSITY_CELL_COUNT * sizeof(double);
 err = cudaMalloc((void**)&d_densityCells, densityBytes);
 if (err != cudaSuccess) {
 PLUTO_LOG("cudaMalloc d_densityCells failed (%zu bytes): %s", densityBytes, cudaGetErrorString(err));
 return false;
 }

 const size_t astRegisterBytes =
  (size_t)PLUTONIUM_DENSITY_CELL_COUNT * (size_t)PLUTONIUM_MAX_AST_INSTRUCTIONS * sizeof(double);
 err = cudaMalloc((void**)&d_astRegisterBuffer, astRegisterBytes);
 if (err != cudaSuccess) {
 PLUTO_LOG("cudaMalloc d_astRegisterBuffer failed (%zu bytes): %s", astRegisterBytes, cudaGetErrorString(err));
 return false;
 }
 PLUTO_LOG("AST VM register scratch allocated: %zu bytes.", astRegisterBytes);

 // Pre-allocate independent CUDA contexts for chunk generation. This removes
 // the old single d_chunk_buffer/d_densityCells/d_astRegisterBuffer mutex path
 // from the hot JNI call and lets Java worker threads keep the GPU fed.
 {
  chunkGenPool.reserve(CHUNK_GEN_POOL_SIZE);
  for (int i = 0; i < CHUNK_GEN_POOL_SIZE; ++i) {
   DeviceChunkGenContext ctx{};
   ctx.inUse = false;
   ctx.stream = nullptr;
   err = cudaMalloc((void**)&ctx.d_chunk, chunkBytes);
   if (err != cudaSuccess) {
    PLUTO_LOG("DeviceChunkGenContext[%d] d_chunk alloc fail: %s", i, cudaGetErrorString(err));
    return false;
   }
   err = cudaMalloc((void**)&ctx.d_densityCells, densityBytes);
   if (err != cudaSuccess) {
    PLUTO_LOG("DeviceChunkGenContext[%d] d_densityCells alloc fail: %s", i, cudaGetErrorString(err));
    return false;
   }
   err = cudaMalloc((void**)&ctx.d_astRegisters, astRegisterBytes);
   if (err != cudaSuccess) {
    PLUTO_LOG("DeviceChunkGenContext[%d] d_astRegisters alloc fail: %s", i, cudaGetErrorString(err));
    return false;
   }
   err = cudaStreamCreateWithFlags(&ctx.stream, cudaStreamNonBlocking);
   if (err != cudaSuccess) {
    PLUTO_LOG("DeviceChunkGenContext[%d] stream create fail: %s", i, cudaGetErrorString(err));
    return false;
   }
   chunkGenPool.push_back(ctx);
  }
  PLUTO_LOG("Device chunk generation pool initialized with %zu contexts.", chunkGenPool.size());
 }

 // Mesh building buffers
 err = cudaMalloc((void**)&d_meshBlocks,18*18*18*sizeof(uint32_t));
 if (err != cudaSuccess) { PLUTO_LOG("d_meshBlocks alloc fail: %s", cudaGetErrorString(err)); return false; }
 err = cudaMalloc((void**)&d_meshVerts, MAX_MESH_VERTS * sizeof(GpuVertex));
 if (err != cudaSuccess) { PLUTO_LOG("d_meshVerts alloc fail"); return false; }
 err = cudaMalloc((void**)&d_meshVertCount, sizeof(int));
 if (err != cudaSuccess) { PLUTO_LOG("d_meshVertCount alloc fail"); return false; }
 
 // Create CUDA event for async mesh kernel completion
 cudaEventCreate(&meshKernelCompleteEvent);
 
 // Initialize buffer pools (alloc 5) improvements)
 initMeshPool();

 // Pre-allocate the device mesh context pool (zero per-frame cudaMalloc cost).
 {
  const int kDeviceMeshPoolSize = 16;
  deviceMeshPool.reserve(kDeviceMeshPoolSize);
  for (int i = 0; i < kDeviceMeshPoolSize; ++i) {
   DeviceMeshContext ctx{};
   ctx.inUse = false;
   cudaError_t e;
   e = cudaMalloc((void**)&ctx.d_blocks, 18 * 18 * 18 * sizeof(uint32_t));
   if (e != cudaSuccess) { PLUTO_LOG("DeviceMeshContext[%d] d_blocks alloc fail: %s", i, cudaGetErrorString(e)); return false; }
   e = cudaMalloc((void**)&ctx.d_verts, MAX_MESH_VERTS * sizeof(GpuVertex));
   if (e != cudaSuccess) { PLUTO_LOG("DeviceMeshContext[%d] d_verts alloc fail: %s", i, cudaGetErrorString(e)); return false; }
   e = cudaMalloc((void**)&ctx.d_vertCount, sizeof(int));
   if (e != cudaSuccess) { PLUTO_LOG("DeviceMeshContext[%d] d_vertCount alloc fail: %s", i, cudaGetErrorString(e)); return false; }
   deviceMeshPool.push_back(ctx);
  }
  PLUTO_LOG("Device mesh pool initialized with %zu contexts (VRAM pre-allocated).", deviceMeshPool.size());
 }

 initBlockBatchPool();
 
 PLUTO_LOG("Mesh buffers ready (max %d verts per section).", MAX_MESH_VERTS);

 // Initialize the CPU Thread Pool
 cpuPool = new ThreadPool(threadCount);
 PLUTO_LOG("Initialized CPU Thread Pool with %d workers.", threadCount);

 PLUTO_LOG("Device front: %p back: %p", (void*)d_front, (void*)d_back);
 PLUTO_LOG("ComputeEngine init complete.");
 return true;
}

void* ComputeEngine::generateChunkNoise(int chunkX, int chunkZ, long seed) {
 //1. Lock the GPU so multiple Java threads don't cause a massive pile-up
 std::lock_guard<std::mutex> lock(chunkMutex);

 //2. Safety first: Did the memory actually allocate during init?
 if (d_chunk_buffer == nullptr || h_chunk_buffer == nullptr || d_densityCells == nullptr || d_astRegisterBuffer == nullptr) {
 printf("[Plutonium/CUDA] ERROR: Chunk buffers are null! Skipping generation.\n");
 return nullptr;
 }

 //3. Launch the kernel safely across the exact volume size
 int cellBlocks = (PLUTONIUM_DENSITY_CELL_COUNT + PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK - 1) / PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK;
 evaluateDensityCellsKernel<<<cellBlocks, PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK>>>(d_densityCells, chunkX, chunkZ, seed, d_astBuffer, d_astRegisterBuffer);
 cudaError_t launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
 printf("[Plutonium/CUDA] DENSITY KERNEL LAUNCH FAILED: %s\n", cudaGetErrorString(launchErr));
 return nullptr;
 }
 cudaError_t err = cudaDeviceSynchronize();
 if (err != cudaSuccess) {
 printf("[Plutonium/CUDA] DENSITY KERNEL CRASH PREVENTED: %s\n", cudaGetErrorString(err));
 return nullptr;
 }

 int voxelBlocks = (CHUNK_VOLUME + 255) / 256;
 fillChunkFromDensityCellsKernel<<<voxelBlocks, 256>>>(d_chunk_buffer, d_densityCells);
 launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
 printf("[Plutonium/CUDA] FILL KERNEL LAUNCH FAILED: %s\n", cudaGetErrorString(launchErr));
 return nullptr;
 }
 
 //4. Wait for GPU and catch any explosive errors
 err = cudaDeviceSynchronize();
 if (err != cudaSuccess) {
 printf("[Plutonium/CUDA] FILL KERNEL CRASH PREVENTED: %s\n", cudaGetErrorString(err));
 return nullptr;
 }

 //5. Copy the shiny new terrain back to the CPU
 err = cudaMemcpy(h_chunk_buffer, d_chunk_buffer, CHUNK_VOLUME * sizeof(VoxelBlock), cudaMemcpyDeviceToHost);
 if (err != cudaSuccess) {
 printf("[Plutonium/CUDA] MEMCPY CRASH PREVENTED: %s\n", cudaGetErrorString(err));
 return nullptr;
 }

 return h_chunk_buffer;
}

bool ComputeEngine::generateChunkNoiseInto(int chunkX, int chunkZ, long seed, void* outBuffer, size_t outBytes) {
 if (outBuffer == nullptr) {
  PLUTO_LOG("generateChunkNoiseInto: null output buffer.");
  return false;
 }

 const size_t chunkBytes = (size_t)CHUNK_VOLUME * sizeof(VoxelBlock);
 if (outBytes < chunkBytes) {
  PLUTO_LOG("generateChunkNoiseInto: output buffer too small (%zu < %zu).", outBytes, chunkBytes);
  return false;
 }

 // Diagnostic: per-call counter so we can correlate a failure with its position
 // in the call stream. Used to find the cliff at ~chunk #838.
 static std::atomic<int> g_callCounter{0};
 static std::atomic<int> g_emptyOutputLogs{0};
 static std::atomic<int> g_stickyErrorLogs{0};
 const int callNumber = g_callCounter.fetch_add(1, std::memory_order_relaxed);

 // Drain any sticky CUDA error from a PREVIOUS unrelated call. If something
 // upstream (mesh kernel, host alloc, etc.) left an error, our local checks
 // would otherwise see "no new error" because cudaGetLastError resets state
 // only on read.
 cudaError_t entryErr = cudaGetLastError();
 if (entryErr != cudaSuccess) {
  int n = g_stickyErrorLogs.fetch_add(1, std::memory_order_relaxed);
  if (n < 16) {
   PLUTO_LOG("generateChunkNoiseInto entry: drained sticky CUDA error (call=%d chunk=%d,%d): %s",
             callNumber, chunkX, chunkZ, cudaGetErrorString(entryErr));
  }
 }

 DeviceChunkGenContext* ctx = acquireChunkGenContext();
 if (ctx == nullptr || ctx->d_chunk == nullptr || ctx->d_densityCells == nullptr ||
     ctx->d_astRegisters == nullptr || ctx->stream == nullptr) {
  PLUTO_LOG("generateChunkNoiseInto: null chunk generation context (call=%d).", callNumber);
  releaseChunkGenContext(ctx);
  return false;
 }

 std::shared_lock<std::shared_mutex> astLock(astBufferMutex);

 // Diagnostic: verify the AST pointer is still alive. If it ever becomes
 // null mid-session, we know we're chasing a use-after-free.
 if (d_astBuffer == nullptr) {
  PLUTO_LOG("generateChunkNoiseInto: d_astBuffer is NULL at call=%d chunk=%d,%d — kernel will fall back to 0.0 density.",
            callNumber, chunkX, chunkZ);
 }

 int cellBlocks = (PLUTONIUM_DENSITY_CELL_COUNT + PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK - 1) / PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK;
 evaluateDensityCellsKernel<<<cellBlocks, PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK, 0, ctx->stream>>>(
  ctx->d_densityCells, chunkX, chunkZ, seed, d_astBuffer, ctx->d_astRegisters);
 cudaError_t launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
  PLUTO_LOG("generateChunkNoiseInto density launch error (call=%d chunk=%d,%d): %s",
            callNumber, chunkX, chunkZ, cudaGetErrorString(launchErr));
  releaseChunkGenContext(ctx);
  return false;
 }

 int voxelBlocks = (CHUNK_VOLUME + 255) / 256;
 fillChunkFromDensityCellsKernel<<<voxelBlocks, 256, 0, ctx->stream>>>(ctx->d_chunk, ctx->d_densityCells);
 launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
  PLUTO_LOG("generateChunkNoiseInto fill launch error (call=%d chunk=%d,%d): %s",
            callNumber, chunkX, chunkZ, cudaGetErrorString(launchErr));
  releaseChunkGenContext(ctx);
  return false;
 }

 cudaError_t err = cudaMemcpyAsync(outBuffer, ctx->d_chunk, chunkBytes, cudaMemcpyDeviceToHost, ctx->stream);
 if (err != cudaSuccess) {
  PLUTO_LOG("generateChunkNoiseInto memcpy enqueue error (call=%d chunk=%d,%d): %s",
            callNumber, chunkX, chunkZ, cudaGetErrorString(err));
  releaseChunkGenContext(ctx);
  return false;
 }

 err = cudaStreamSynchronize(ctx->stream);
 if (err != cudaSuccess) {
  PLUTO_LOG("generateChunkNoiseInto stream error (call=%d chunk=%d,%d): %s",
            callNumber, chunkX, chunkZ, cudaGetErrorString(err));
  releaseChunkGenContext(ctx);
  return false;
 }

 // Diagnostic: spot-check the output for the "everything is air" failure mode.
 // We probe one voxel deep underground (y=0 worldY = bufferY 64) where stone
 // is overwhelmingly likely in overworld terrain. If THAT is air, the kernel
 // didn't actually populate the chunk and we want to know why.
 // VoxelBlock layout: 3 bytes (blockID, lightLevel, meta). Index encoding from
 // the kernel: outIdx = (y << 8) | (z << 4) | x → for x=0,z=0,y=64 → 64<<8 = 16384.
 const unsigned char* outBytes_ = (const unsigned char*)outBuffer;
 unsigned char deepBlockId = outBytes_[16384 * sizeof(VoxelBlock)];
 if (deepBlockId == 0) {
  int n = g_emptyOutputLogs.fetch_add(1, std::memory_order_relaxed);
  if (n < 8) {
   cudaError_t postSyncErr = cudaPeekAtLastError();
   // Sample one density cell directly from device memory.
   double sampleDensity = 0.0;
   cudaMemcpy(&sampleDensity, ctx->d_densityCells, sizeof(double), cudaMemcpyDeviceToHost);
   PLUTO_LOG("generateChunkNoiseInto OUTPUT-EMPTY (call=%d chunk=%d,%d): deepBlockId=0, "
             "d_astBuffer=%p, sampleDensityCell0=%g, peekErr=%s",
             callNumber, chunkX, chunkZ, (void*)d_astBuffer, sampleDensity,
             cudaGetErrorString(postSyncErr));
  }
 }

 releaseChunkGenContext(ctx);
 return true;
}

bool ComputeEngine::evaluateChunkDensityCells(int chunkX, int chunkZ, long seed, double* outValues, int count) {
 if (!outValues || count < PLUTONIUM_DENSITY_CELL_COUNT) {
  PLUTO_LOG("evaluateChunkDensityCells: invalid output (count=%d required=%d).",
            count, PLUTONIUM_DENSITY_CELL_COUNT);
  return false;
 }

 cudaSetDevice(s_deviceIndex);

 DeviceChunkGenContext* ctx = acquireChunkGenContext();
 if (ctx == nullptr || ctx->d_densityCells == nullptr ||
     ctx->d_astRegisters == nullptr || ctx->stream == nullptr) {
  PLUTO_LOG("evaluateChunkDensityCells: null chunk generation context.");
  releaseChunkGenContext(ctx);
  return false;
 }

 std::shared_lock<std::shared_mutex> astLock(astBufferMutex);
 if (!d_astBuffer) {
  PLUTO_LOG("evaluateChunkDensityCells: no AST uploaded.");
  releaseChunkGenContext(ctx);
  return false;
 }

 const int cellBlocks =
  (PLUTONIUM_DENSITY_CELL_COUNT + PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK - 1) /
  PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK;
 evaluateDensityCellsKernel<<<cellBlocks, PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK, 0, ctx->stream>>>(
  ctx->d_densityCells, chunkX, chunkZ, seed, d_astBuffer, ctx->d_astRegisters);
 cudaError_t launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
  PLUTO_LOG("evaluateChunkDensityCells launch error (chunk=%d,%d): %s",
            chunkX, chunkZ, cudaGetErrorString(launchErr));
  releaseChunkGenContext(ctx);
  return false;
 }

 const size_t outBytes = (size_t)PLUTONIUM_DENSITY_CELL_COUNT * sizeof(double);
 cudaError_t err = cudaMemcpyAsync(
  outValues, ctx->d_densityCells, outBytes, cudaMemcpyDeviceToHost, ctx->stream);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateChunkDensityCells copy enqueue error (chunk=%d,%d): %s",
            chunkX, chunkZ, cudaGetErrorString(err));
  releaseChunkGenContext(ctx);
  return false;
 }

 err = cudaStreamSynchronize(ctx->stream);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateChunkDensityCells stream error (chunk=%d,%d): %s",
            chunkX, chunkZ, cudaGetErrorString(err));
  releaseChunkGenContext(ctx);
  return false;
 }

 releaseChunkGenContext(ctx);
 return true;
}

bool ComputeEngine::evaluateDensityPoints(const int32_t* coordsXYZ, double* outValues, int count) {
 if (!coordsXYZ || !outValues || count <= 0) {
  PLUTO_LOG("evaluateDensityPoints: invalid input (count=%d).", count);
  return false;
 }
 if (count > 2048) {
  PLUTO_LOG("evaluateDensityPoints: count too high (%d > 2048).", count);
  return false;
 }

 cudaSetDevice(s_deviceIndex);

 std::shared_lock<std::shared_mutex> astLock(astBufferMutex);
 if (!d_astBuffer) {
  PLUTO_LOG("evaluateDensityPoints: no AST uploaded.");
  return false;
 }

 int32_t* d_coords = nullptr;
 double* d_out = nullptr;
 double* d_regs = nullptr;
 const size_t coordBytes = (size_t)count * 3u * sizeof(int32_t);
 const size_t outBytes = (size_t)count * sizeof(double);
 const size_t regBytes = (size_t)count * (size_t)PLUTONIUM_MAX_AST_INSTRUCTIONS * sizeof(double);

 cudaError_t err = cudaMalloc((void**)&d_coords, coordBytes);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: d_coords alloc failed: %s", cudaGetErrorString(err));
  return false;
 }
 err = cudaMalloc((void**)&d_out, outBytes);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: d_out alloc failed: %s", cudaGetErrorString(err));
  cudaFree(d_coords);
  return false;
 }
 err = cudaMalloc((void**)&d_regs, regBytes);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: d_regs alloc failed (%zu bytes): %s", regBytes, cudaGetErrorString(err));
  cudaFree(d_out);
  cudaFree(d_coords);
  return false;
 }

 err = cudaMemcpy(d_coords, coordsXYZ, coordBytes, cudaMemcpyHostToDevice);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: coords copy failed: %s", cudaGetErrorString(err));
  cudaFree(d_regs);
  cudaFree(d_out);
  cudaFree(d_coords);
  return false;
 }

 const int threads = 128;
 const int blocks = (count + threads - 1) / threads;
 evaluateDensityPointsKernel<<<blocks, threads>>>(d_coords, d_out, count, d_astBuffer, d_regs);
 cudaError_t launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: kernel launch failed: %s", cudaGetErrorString(launchErr));
  cudaFree(d_regs);
  cudaFree(d_out);
  cudaFree(d_coords);
  return false;
 }
 err = cudaDeviceSynchronize();
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: kernel failed: %s", cudaGetErrorString(err));
  cudaFree(d_regs);
  cudaFree(d_out);
  cudaFree(d_coords);
  return false;
 }
 err = cudaMemcpy(outValues, d_out, outBytes, cudaMemcpyDeviceToHost);
 if (err != cudaSuccess) {
  PLUTO_LOG("evaluateDensityPoints: result copy failed: %s", cudaGetErrorString(err));
  cudaFree(d_regs);
  cudaFree(d_out);
  cudaFree(d_coords);
  return false;
 }

 cudaFree(d_regs);
 cudaFree(d_out);
 cudaFree(d_coords);
 return true;
}

void* ComputeEngine::generateChunkOnGpu(int cx, int cz, long seed) {
 std::lock_guard<std::mutex> lock(chunkMutex);
 if (!d_chunk_buffer || !h_chunk_buffer || !d_densityCells || !d_astRegisterBuffer) {
 PLUTO_LOG("generateChunkOnGpu: buffers null, skipping.");
 return nullptr;
 }
 int threadsPerBlock = PLUTONIUM_WORLDGEN_THREADS_PER_BLOCK;
 int blocksPerGrid = (PLUTONIUM_DENSITY_CELL_COUNT + threadsPerBlock -1) / threadsPerBlock;
 evaluateDensityCellsKernel<<<blocksPerGrid, threadsPerBlock>>>(d_densityCells, cx, cz, seed, d_astBuffer, d_astRegisterBuffer);
 cudaError_t launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
 PLUTO_LOG("generateChunkOnGpu density launch error: %s", cudaGetErrorString(launchErr));
 return nullptr;
 }
 cudaError_t err = cudaDeviceSynchronize();
 if (err != cudaSuccess) {
 PLUTO_LOG("generateChunkOnGpu density kernel error: %s", cudaGetErrorString(err));
 return nullptr;
 }
 int fillBlocks = (CHUNK_VOLUME + 255) / 256;
 fillChunkFromDensityCellsKernel<<<fillBlocks, 256>>>(d_chunk_buffer, d_densityCells);
 launchErr = cudaGetLastError();
 if (launchErr != cudaSuccess) {
 PLUTO_LOG("generateChunkOnGpu fill launch error: %s", cudaGetErrorString(launchErr));
 return nullptr;
 }
 err = cudaDeviceSynchronize();
 if (err != cudaSuccess) {
 PLUTO_LOG("generateChunkOnGpu fill kernel error: %s", cudaGetErrorString(err));
 return nullptr;
 }
 err = cudaMemcpy(h_chunk_buffer, d_chunk_buffer,
 CHUNK_VOLUME * sizeof(VoxelBlock), cudaMemcpyDeviceToHost);
 if (err != cudaSuccess) {
 PLUTO_LOG("generateChunkOnGpu memcpy error: %s", cudaGetErrorString(err));
 return nullptr;
 }
 return h_chunk_buffer;
}

void* ComputeEngine::getPinnedWorldPtr() {
    return d_pinned_world;
}

void ComputeEngine::syncFromJava() {
 // For now, we just use this as a 'Heartbeat'
 // Later, this is where we will swap buffers for Entity data
 // std::lock_guard<std::mutex> lock(bufferMutex);
}

void ComputeEngine::startPhysics() {
    if (running.load()) { PLUTO_LOG("Physics thread already running."); return; }
    PLUTO_LOG("Spawning physics thread...");
    running.store(true);
    physicsThread = std::thread(&ComputeEngine::physicsLoop, this);
    PLUTO_LOG("Physics thread spawned.");
}

void ComputeEngine::stop() {
    if (running.load()) {
        PLUTO_LOG("Stopping physics thread...");
        running.store(false);
        if (physicsThread.joinable()) physicsThread.join();
        PLUTO_LOG("Physics thread stopped.");
    }
}

void ComputeEngine::physicsLoop() {
 cudaSetDevice(s_deviceIndex);
 while (running.load()) {
 // C2ME Logic: Only use GPU power if a 'Tick' occurred
 if (needsUpdate.load()) {
 runPhysicsKernel();
 syncToPinnedMemory();
 needsUpdate.store(false); // Reset the flag
 } else {
 // High-efficiency sleep. This is why Thread10 was maxed.
 // By sleeping for1ms, we drop CPU usage from100% to <2%.
 std::this_thread::sleep_for(std::chrono::milliseconds(1));
 }
 }
}

//1. The Physics Calculation (Moved out of the loop for clean logic)
void ComputeEngine::runPhysicsKernel() {
 const int W = streamWidth;
 const int H = streamHeight;
 size_t worldBytes = (size_t)W * H * sizeof(VoxelBlock);
 (void)worldBytes;
 // DISABLED: 6MB PCIe copy every tick was destroying framerate.
 // {
 //  std::lock_guard<std::mutex> lock(worldMutex);
 //  cudaError_t err = cudaMemcpy(d_front, d_pinned_world, worldBytes, cudaMemcpyHostToDevice);
 //  if (err != cudaSuccess) {
 //   PLUTO_LOG("runPhysicsKernel H2D failed: %s", cudaGetErrorString(err));
 //   return;
 //  }
 // }
 dim3 block(16,16);
 dim3 grid((W +15) /16, (H +15) /16);
 (void)block; (void)grid;
 // physicsStep<<<grid, block>>>(d_front, d_back, W, H);
}

//2. The Memory Sync (Only happens when needed)
void ComputeEngine::syncToPinnedMemory() {
 size_t worldBytes = (size_t)streamWidth * streamHeight * sizeof(VoxelBlock);
 (void)worldBytes;
 // DISABLED: part of the 6MB/tick PCIe sync that killed framerate.
 // std::lock_guard<std::mutex> lock(worldMutex);
 // cudaError_t err = cudaMemcpy(d_pinned_world, d_back, worldBytes, cudaMemcpyDeviceToHost);
 // if (err != cudaSuccess) {
 //  PLUTO_LOG("syncToPinnedMemory D2H failed: %s", cudaGetErrorString(err));
 //  return;
 // }
 // std::swap(d_front, d_back);
}

void ComputeEngine::setBlockNative(int x, int y, int z, unsigned char id, unsigned char meta, unsigned char light) {
 (void)y;
 std::lock_guard<std::mutex> lock(worldMutex);
 // 2D slab: reject full Minecraft world coords (avoids int overflow / memory corruption)
 if (x < 0 || z < 0 || x >= streamWidth || z >= streamHeight) {
  return;
 }
 int idx = z * streamWidth + x;
 if (idx >= 0 && idx < (streamWidth * streamHeight)) {
  d_pinned_world[idx] = VoxelBlock{ id, light, meta };
 }
}

static int32_t plutoniumReadI32LE(const unsigned char* p) {
 uint32_t u = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
 return (int32_t)u;
}

void ComputeEngine::applyJniBlockBatch(const unsigned char* data, int count) {
 if (!data || count <= 0 || !d_pinned_world) return;
 std::lock_guard<std::mutex> lock(worldMutex);
 for (int i = 0; i < count; i++) {
  int off = i * 16;
  int x = plutoniumReadI32LE(data + off);
  int y = plutoniumReadI32LE(data + off + 4);
  int z = plutoniumReadI32LE(data + off + 8);
  (void)y;
  unsigned char id = data[off + 12];
  if (x < 0 || z < 0 || x >= streamWidth || z >= streamHeight) continue;
  int idx = z * streamWidth + x;
  if (idx >= 0 && idx < (streamWidth * streamHeight)) {
   d_pinned_world[idx] = VoxelBlock{ id, 15, 0 };
  }
 }
 needsUpdate.store(true);
}

void ComputeEngine::enqueueJniBlockBatch(std::vector<unsigned char>&& payload, int count) {
 if (count <= 0 || payload.empty()) return;
 if ((size_t)count * 16 != payload.size()) {
  PLUTO_LOG("enqueueJniBlockBatch: size mismatch (count=%d, bytes=%zu)", count, payload.size());
  return;
 }
 if (!cpuPool) {
  applyJniBlockBatch(payload.data(), count);
  return;
 }
 auto blob = std::make_shared<std::vector<unsigned char>>(std::move(payload));
 submitCpuTask([this, blob, count]() {
  applyJniBlockBatch(blob->data(), count);
 });
}

void ComputeEngine::processEntityAI(int id, float x, float y, float z, float yaw, float pitch) {
 // This code runs on one of your12 background threads!
 // Example: Simple "Gravity" check using the Voxel data
 // int voxelIdx = calculateIndex(x, y -1, z);
 // if (d_pinned_world[voxelIdx].blockID ==0) { ... move entity down ... }
 // Logic goes here...
}

void ComputeEngine::uploadWorldData(int cx, int cz, void* data) {
 (void)cx;
 (void)cz;
 if (!data) return;
 size_t bytes = (size_t)CHUNK_VOLUME * sizeof(VoxelBlock);
 cudaError_t err = cudaMemcpy(d_front, data, bytes, cudaMemcpyHostToDevice);
 if (err != cudaSuccess) {
  PLUTO_LOG("uploadWorldData cudaMemcpy failed: %s", cudaGetErrorString(err));
  return;
 }
 PLUTO_LOG("uploadWorldData: copied %zu bytes into d_front (chunk stub)", bytes);
}

void ComputeEngine::uploadAST(const void* buffer, size_t size) {
 std::unique_lock<std::shared_mutex> lock(astBufferMutex);

 if (d_astBuffer) {
  cudaFree(d_astBuffer);
  d_astBuffer = nullptr;
 }

 if (!buffer || size == 0) {
  PLUTO_LOG("uploadAST: cleared AST buffer.");
  return;
 }

 cudaError_t err = cudaMalloc(&d_astBuffer, size);
 if (err != cudaSuccess) {
  PLUTO_LOG("uploadAST cudaMalloc failed: %s", cudaGetErrorString(err));
  d_astBuffer = nullptr;
  return;
 }

 err = cudaMemcpy(d_astBuffer, buffer, size, cudaMemcpyHostToDevice);
 if (err != cudaSuccess) {
  PLUTO_LOG("uploadAST cudaMemcpy failed: %s", cudaGetErrorString(err));
  cudaFree(d_astBuffer);
  d_astBuffer = nullptr;
  return;
 }

 PLUTO_LOG("uploadAST: copied %zu bytes to device AST buffer.", size);
}

void ComputeEngine::updateBlockBatch(const void* buffer, int count) {
 const unsigned char* data = static_cast<const unsigned char*>(buffer);
 // Each block: x, y, z, id (4 bytes)
 if (count >1000) {
 // Bulk update: update d_pinned_world, then memcpy to GPU
 std::vector<VoxelBlock> tempWorld(d_pinned_world, d_pinned_world + streamWidth * streamHeight);
 for (int i =0; i < count; ++i) {
 int x = data[i *4 +0];
 int y = data[i *4 +1];
 int z = data[i *4 +2];
 (void)y;  // Suppress unused variable warning
 unsigned char id = data[i *4 +3];
 int idx = (z * streamWidth) + x; //2D wrap, adjust if3D
 if (idx >=0 && idx < streamWidth * streamHeight) {
 tempWorld[idx].blockID = id;
 }
 }
 // Copy the whole buffer to both pinned and device memory
 memcpy(d_pinned_world, tempWorld.data(), streamWidth * streamHeight * sizeof(VoxelBlock));
 cudaMemcpyAsync(d_front, d_pinned_world, streamWidth * streamHeight * sizeof(VoxelBlock), cudaMemcpyHostToDevice,0);
 } else {
 for (int i =0; i < count; ++i) {
 int x = data[i *4 +0];
 int y = data[i *4 +1];
 int z = data[i *4 +2];
 (void)y;  // Suppress unused variable warning
 unsigned char id = data[i *4 +3];
 int idx = (z * streamWidth) + x; //2D wrap, adjust if3D
 if (idx >=0 && idx < streamWidth * streamHeight) {
 d_pinned_world[idx].blockID = id;
 }
 }
 }
}

// ── Block colour table ────────────────────────────────────────────────────────
#pragma warning(push)
#pragma warning(disable: 177)  // Suppress "label was declared but never referenced"
__device__ void blockColour(int id, float light,
 unsigned char& r, unsigned char& g, unsigned char& b) {
 unsigned char br, bg, bb;
 switch (id) {
 case1: br=128;bg=128;bb=128; break; // stone
 case2: br=106;bg=127;bb=51; break; // grass
 case3: br=134;bg=96;bb=67; break; // dirt
 case4: br=108;bg=108;bb=108; break; // cobblestone
 case5: br=162;bg=130;bb=78; break; // planks
 case7: br=60;bg=60;bb=60; break; // bedrock
 case12: br=220;bg=210;bb=170; break; // sand
 case13: br=120;bg=120;bb=120; break; // gravel
 case17: br=102;bg=82;bb=51; break; // log
 case18: br=60;bg=100;bb=45; break; // leaves
 default:
 br=(unsigned char)((id*77)&255);
 bg=(unsigned char)((id*149)&255);
 bb=(unsigned char)((id*211)&255);
 }
 r=(unsigned char)(br*light);
 g=(unsigned char)(bg*light);
 b=(unsigned char)(bb*light);
}
#pragma warning(pop)

__device__ static inline uint32_t packVertexColorRGBA(unsigned char r, unsigned char g, unsigned char b, unsigned char a) {
 return (uint32_t)r
  | ((uint32_t)g << 8)
  | ((uint32_t)b << 16)
  | ((uint32_t)a << 24);
}

// ── Face mesh kernel ──────────────────────────────────────────────────────────
// Each thread = one face of one block (16*16*16*6 =24576 threads per section)
#pragma warning(push)
#pragma warning(disable: 177)  // Suppress "label was declared but never referenced"
__global__ void buildFaceMeshKernel(
 const uint32_t* blockGrid, //18x18x18 padded section, full block-state IDs
 ComputeEngine::GpuVertex* outVerts,
 int* vertCount,
 int maxVerts,
 const CudaUVRect* uvTable,
 int uvTableCount,
 float ox, float oy, float oz)
{
 int tid = blockIdx.x * blockDim.x + threadIdx.x;
 if (tid >=16*16*16*6) return;
 int face = tid %6;
 int bl = tid /6;
 int bx = bl %16;
 int by = (bl /16) %16;
 int bz = bl / (16*16);
 // Read self (padded grid, offset +1 in each axis)
 uint32_t self = blockGrid[(bx+1) + (by+1)*18 + (bz+1)*18*18];
 if (self ==0) return;
 // Neighbour offsets per face: +X -X +Y -Y +Z -Z
 int dnx=0, dny=0, dnz=0;
 if (face==0) dnx=1; else if (face==1) dnx=-1;
 else if (face==2) dny=1; else if (face==3) dny=-1;
 else if (face==4) dnz=1; else dnz=-1;
 uint32_t nbr = blockGrid[(bx+1+dnx) + (by+1+dny)*18 + (bz+1+dnz)*18*18];
 if (nbr !=0) return; // occluded face
 //6 verts (2 triangles) per face
 int slot = atomicAdd(vertCount,6);
 if (slot +6 > maxVerts) { atomicSub(vertCount,6); return; }
 float wx=ox+bx, wy=oy+by, wz=oz+bz;
 // Face directional light
 float lt;
 if (face==2) lt=1.0f; else if (face==3) lt=0.5f;
 else if (face==0||face==1) lt=0.8f; else lt=0.7f;
 unsigned char shade = (unsigned char)(lt * 255.0f + 0.5f);
 uint32_t color = packVertexColorRGBA(shade, shade, shade, 255);
 CudaUVRect uv = {0.f, 0.f, 0.f, 0.f};
 if (uvTable && self < (uint32_t)uvTableCount) {
  uv = uvTable[self];
 }
 float uw = uv.u1 - uv.u0;
 float vh = uv.v1 - uv.v0;
 ComputeEngine::GpuVertex q[6];
#define V(X,Y,Z,UF,VF) ComputeEngine::GpuVertex{(X), (Y), (Z), color, uv.u0 + (UF) * uw, uv.v0 + (VF) * vh, 240u, 240u}
 switch (face) {
 case 0:
  q[0]=V(wx+1,wy,   wz,   0.f,1.f); q[1]=V(wx+1,wy+1, wz,   0.f,0.f);
  q[2]=V(wx+1,wy,   wz+1, 1.f,1.f); q[3]=V(wx+1,wy+1, wz,   0.f,0.f);
  q[4]=V(wx+1,wy+1, wz+1, 1.f,0.f); q[5]=V(wx+1,wy,   wz+1, 1.f,1.f); break;
 case 1:
  q[0]=V(wx,wy,   wz,   1.f,1.f); q[1]=V(wx,wy,   wz+1, 0.f,1.f);
  q[2]=V(wx,wy+1, wz,   1.f,0.f); q[3]=V(wx,wy+1, wz,   1.f,0.f);
  q[4]=V(wx,wy,   wz+1, 0.f,1.f); q[5]=V(wx,wy+1, wz+1, 0.f,0.f); break;
 case 2:
  q[0]=V(wx,  wy+1,wz,   0.f,0.f); q[1]=V(wx,  wy+1,wz+1, 0.f,1.f);
  q[2]=V(wx+1,wy+1,wz,   1.f,0.f); q[3]=V(wx+1,wy+1,wz,   1.f,0.f);
  q[4]=V(wx,  wy+1,wz+1, 0.f,1.f); q[5]=V(wx+1,wy+1,wz+1, 1.f,1.f); break;
 case 3:
  q[0]=V(wx,  wy,wz,   0.f,0.f); q[1]=V(wx+1,wy,wz,   1.f,0.f);
  q[2]=V(wx,  wy,wz+1, 0.f,1.f); q[3]=V(wx,  wy,wz+1, 0.f,1.f);
  q[4]=V(wx+1,wy,wz,   1.f,0.f); q[5]=V(wx+1,wy,wz+1, 1.f,1.f); break;
 case 4:
  q[0]=V(wx,  wy,   wz+1, 0.f,1.f); q[1]=V(wx+1,wy,   wz+1, 1.f,1.f);
  q[2]=V(wx,  wy+1, wz+1, 0.f,0.f); q[3]=V(wx,  wy+1, wz+1, 0.f,0.f);
  q[4]=V(wx+1,wy,   wz+1, 1.f,1.f); q[5]=V(wx+1,wy+1, wz+1, 1.f,0.f); break;
 case 5:
  q[0]=V(wx,  wy,   wz, 1.f,1.f); q[1]=V(wx,  wy+1, wz, 1.f,0.f);
  q[2]=V(wx+1,wy,   wz, 0.f,1.f); q[3]=V(wx+1,wy,   wz, 0.f,1.f);
  q[4]=V(wx,  wy+1, wz, 1.f,0.f); q[5]=V(wx+1,wy+1, wz, 0.f,0.f); break;
 }
#undef V
 outVerts[slot+0]=q[0]; outVerts[slot+1]=q[1]; outVerts[slot+2]=q[2];
 outVerts[slot+3]=q[3]; outVerts[slot+4]=q[4]; outVerts[slot+5]=q[5];
}
#pragma warning(pop)

// ── Device mesh context pool ──────────────────────────────────────────────────
ComputeEngine::DeviceMeshContext* ComputeEngine::acquireDeviceMeshContext() {
 for (;;) {
  {
   std::lock_guard<std::mutex> lock(deviceMeshPoolMutex);
   for (auto& ctx : deviceMeshPool) {
    if (!ctx.inUse) {
     ctx.inUse = true;
     return &ctx;
    }
   }
  }
  std::this_thread::yield();
 }
}

void ComputeEngine::releaseDeviceMeshContext(DeviceMeshContext* ctx) {
 if (!ctx) return;
 std::lock_guard<std::mutex> lock(deviceMeshPoolMutex);
 ctx->inUse = false;
}

// ── Device chunk generation context pool ──────────────────────────────────────
ComputeEngine::DeviceChunkGenContext* ComputeEngine::acquireChunkGenContext() {
 for (;;) {
  {
   std::lock_guard<std::mutex> lock(chunkGenPoolMutex);
   for (auto& ctx : chunkGenPool) {
    if (!ctx.inUse) {
     ctx.inUse = true;
     return &ctx;
    }
   }
  }
  std::this_thread::yield();
 }
}

void ComputeEngine::releaseChunkGenContext(DeviceChunkGenContext* ctx) {
 if (!ctx) return;
 std::lock_guard<std::mutex> lock(chunkGenPoolMutex);
 ctx->inUse = false;
}

// ── ComputeEngine::buildSectionMesh ───────────────────────────────────────────
// Zero-allocation path: pull a pre-allocated DeviceMeshContext out of the pool,
// run the kernel against its VRAM, and release. No cudaMalloc on the hot path,
// so the NVIDIA driver's global allocation lock never becomes a bottleneck.
int ComputeEngine::buildSectionMesh(
 const uint32_t* blockData18, GpuVertex* outVertsCPU,
 int maxVerts, float ox, float oy, float oz)
{
 DeviceMeshContext* ctx = acquireDeviceMeshContext();

 cudaError_t err = cudaMemcpy(ctx->d_blocks, blockData18, 18*18*18*sizeof(uint32_t), cudaMemcpyHostToDevice);
 if (err != cudaSuccess) {
  PLUTO_LOG("mesh H2D fail: %s", cudaGetErrorString(err));
  releaseDeviceMeshContext(ctx);
  return -1;
 }

 int zero = 0;
 cudaMemcpy(ctx->d_vertCount, &zero, sizeof(int), cudaMemcpyHostToDevice);

 int threads = 16*16*16*6;
 int blockSz = 256;
 int gridSz  = (threads + blockSz - 1) / blockSz;
 CudaUVRect* uvTable = nullptr;
 int uvTableCount = 0;
 {
  std::lock_guard<std::mutex> lock(g_cudaUvTableMutex);
  uvTable = g_cudaUvTable;
  uvTableCount = g_cudaUvTableCount;
 }
 buildFaceMeshKernel<<<gridSz, blockSz>>>(
  ctx->d_blocks, ctx->d_verts, ctx->d_vertCount,
  (maxVerts < MAX_MESH_VERTS ? maxVerts : MAX_MESH_VERTS),
  uvTable, uvTableCount, ox, oy, oz);

 err = cudaGetLastError();
 if (err != cudaSuccess) {
  PLUTO_LOG("mesh kernel launch fail: %s (uvTable=%p uvCount=%d)", cudaGetErrorString(err), (void*)uvTable, uvTableCount);
  releaseDeviceMeshContext(ctx);
  return -1;
 }

 // Implicit sync: the D2H cudaMemcpy below will not start until the kernel finishes.
 int count = 0;
 err = cudaMemcpy(&count, ctx->d_vertCount, sizeof(int), cudaMemcpyDeviceToHost);
 if (err != cudaSuccess) {
  PLUTO_LOG("mesh vertcount D2H fail: %s", cudaGetErrorString(err));
  releaseDeviceMeshContext(ctx);
  return -1;
 }

 if (count <= 0) {
  releaseDeviceMeshContext(ctx);
  return 0;
 }
 if (count > maxVerts || count > MAX_MESH_VERTS) {
  PLUTO_LOG("mesh vertcount overflow reported count=%d maxVerts=%d maxMeshVerts=%d", count, maxVerts, MAX_MESH_VERTS);
 }
 count = (count < MAX_MESH_VERTS ? count : MAX_MESH_VERTS);

 err = cudaMemcpy(outVertsCPU, ctx->d_verts, count * sizeof(GpuVertex), cudaMemcpyDeviceToHost);
 if (err != cudaSuccess) {
  PLUTO_LOG("mesh D2H fail: %s", cudaGetErrorString(err));
  releaseDeviceMeshContext(ctx);
  return -1;
 }

 releaseDeviceMeshContext(ctx);
 return count;
}

// ── Pool Management Functions (OPTIMIZATION 5) ──────────────────────────────
void ComputeEngine::initMeshPool() {
 std::lock_guard<std::mutex> lock(meshPoolMutex);
 for (int i = 0; i < MESH_POOL_SIZE; i++) {
  GpuVertex* hostBuf = (GpuVertex*)malloc(MAX_MESH_VERTS * sizeof(GpuVertex));
  if (hostBuf) {
   meshBufferPool.push_back({(void*)hostBuf, false});
  }
 }
 PLUTO_LOG("Mesh pool initialized with %zu buffers", meshBufferPool.size());
}

void ComputeEngine::cleanupMeshPool() {
 std::lock_guard<std::mutex> lock(meshPoolMutex);
 for (const auto& entry : meshBufferPool) {
  if (entry.hostBuffer) {
   free(entry.hostBuffer);
  }
 }
 meshBufferPool.clear();
 PLUTO_LOG("Mesh pool cleaned up");
}

ComputeEngine::GpuVertex* ComputeEngine::acquireMeshBuffer() {
 std::lock_guard<std::mutex> lock(meshPoolMutex);
 for (auto& entry : meshBufferPool) {
  if (!entry.inUse) {
   entry.inUse = true;
   return (GpuVertex*)entry.hostBuffer;
  }
 }
 // Allocate fallback if pool exhausted (should be rare)
 PLUTO_LOG("Mesh pool exhausted, allocating fallback buffer");
 return (GpuVertex*)malloc(MAX_MESH_VERTS * sizeof(GpuVertex));
}

void ComputeEngine::releaseMeshBuffer(GpuVertex* buffer) {
 if (!buffer) return;
 std::lock_guard<std::mutex> lock(meshPoolMutex);
 for (auto& entry : meshBufferPool) {
  if ((GpuVertex*)entry.hostBuffer == buffer) {
   entry.inUse = false;
   return;
  }
 }
 // Fallback buffer not in pool, just free it
 free(buffer);
}

void ComputeEngine::initBlockBatchPool() {
 std::lock_guard<std::mutex> lock(blockBatchPoolMutex);
 for (int i = 0; i < BLOCK_BATCH_POOL_SIZE; i++) {
  const size_t BUF_SIZE = 16 * 1024;  // 16KB per batch buffer
  unsigned char* buf = (unsigned char*)malloc(BUF_SIZE);
  if (buf) {
   blockBatchBufferPool.push_back({buf, BUF_SIZE, false});
  }
 }
 PLUTO_LOG("Block batch pool initialized with %zu buffers", blockBatchBufferPool.size());
}

void ComputeEngine::cleanupBlockBatchPool() {
 std::lock_guard<std::mutex> lock(blockBatchPoolMutex);
 for (const auto& entry : blockBatchBufferPool) {
  if (entry.hostBuffer) {
   free(entry.hostBuffer);
  }
 }
 blockBatchBufferPool.clear();
 PLUTO_LOG("Block batch pool cleaned up");
}

unsigned char* ComputeEngine::acquireBlockBatchBuffer(size_t size) {
 std::lock_guard<std::mutex> lock(blockBatchPoolMutex);
 for (auto& entry : blockBatchBufferPool) {
  if (!entry.inUse && entry.capacity >= size) {
   entry.inUse = true;
   return entry.hostBuffer;
  }
 }
 // Allocate fallback if pool exhausted
 PLUTO_LOG("Block batch pool exhausted for size %zu, allocating fallback", size);
 return (unsigned char*)malloc(size);
}

void ComputeEngine::releaseBlockBatchBuffer(unsigned char* buffer) {
 if (!buffer) return;
 std::lock_guard<std::mutex> lock(blockBatchPoolMutex);
 for (auto& entry : blockBatchBufferPool) {
  if (entry.hostBuffer == buffer) {
   entry.inUse = false;
   return;
  }
 }
 // Fallback buffer not in pool, just free it
 free(buffer);
}
