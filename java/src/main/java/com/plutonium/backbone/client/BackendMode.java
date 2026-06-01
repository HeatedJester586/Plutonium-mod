package com.plutonium.backbone.client;

/**
 * Selected render backend.
 * OPENGL = vanilla only (compositor disabled).
 * DX12 / VULKAN = Plutonium compositor + native backend active.
 */
public enum BackendMode {
    OPENGL,
    DX12,
    VULKAN;

    public static BackendMode fromString(String s) {
        if (s == null) return OPENGL;
        try {
            return BackendMode.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return OPENGL;
        }
    }
}