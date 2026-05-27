package com.plutonium.backbone.client;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11;

public final class InteropCapsSnippet {
    private static boolean printed = false;

    private InteropCapsSnippet() {}

    public static void printCapsOnce() {
        if (printed) return;
        printed = true;

        GLCapabilities caps = GL.getCapabilities();
        System.out.println("---- Plutonium GL interop caps ----");
        System.out.println("GL_EXT_memory_object: "        + caps.GL_EXT_memory_object);
        System.out.println("GL_EXT_memory_object_win32: "  + caps.GL_EXT_memory_object_win32);
        System.out.println("GL_EXT_semaphore: "            + caps.GL_EXT_semaphore);
        System.out.println("GL_EXT_semaphore_win32: "      + caps.GL_EXT_semaphore_win32);
        System.out.println("GL version: "  + GL11.glGetString(GL11.GL_VERSION));
        System.out.println("GL renderer: " + GL11.glGetString(GL11.GL_RENDERER));
        System.out.println("GL vendor: "   + GL11.glGetString(GL11.GL_VENDOR));
        System.out.println("----------------------------------");
    }
}