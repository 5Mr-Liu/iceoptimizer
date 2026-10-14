package dev.rlcraft.ice.early;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Startup-only ownership decision shared by hooks and the immutable client configuration. */
public final class EarlyRendererOwnership {
    private static final Set<String> MODULES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "modern-frame-coordinator", "modern-terrain-backend", "modern-terrain-mdi",
        "modern-terrain-persistent-mapping", "modern-visibility-hzb", "modern-visibility-grid",
        "modern-entity-backend", "modern-tesr-backend", "modern-particle-backend",
        "modern-texture-stream", "modern-texture-persistent-ring", "modern-texture-visibility",
        "modern-hud-stream", "optifine-region-backend", "optifine-shader-bridge",
        "legacy-gl-island", "render-validation", "fbp-particle-adapter")));
    private static volatile boolean externalTerrainRenderer;
    private static volatile boolean nativeTerrainRenderer;

    private EarlyRendererOwnership() { }
    public static void initialize(boolean external) { initialize(external, false); }
    public static void initialize(boolean external, boolean nativeRenderer) {
        externalTerrainRenderer = external;
        nativeTerrainRenderer = nativeRenderer && !external;
    }
    public static boolean hasExternalTerrainRenderer() { return externalTerrainRenderer; }
    public static boolean hasNativeTerrainRenderer() { return nativeTerrainRenderer; }
    public static boolean hasDedicatedTerrainRenderer() {
        return externalTerrainRenderer || nativeTerrainRenderer;
    }
    public static boolean isRendererModule(String module) { return MODULES.contains(module); }
}
