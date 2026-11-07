package dev.rlcraft.ice.optimizer.render.terrain;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/** Called at the end of OpenGlHelper initialization, before RenderGlobal exists. */
public final class NativeTerrainStartup {
    private static final NativeTerrainBackendChoice CHOICE = new NativeTerrainBackendChoice();
    private static boolean radialFog;

    private NativeTerrainStartup() { }

    public static void initialize() {
        if (CHOICE.initialized()) return;
        ContextCapabilities capabilities = GLContext.getCapabilities();
        int backend = CHOICE.select(capabilities.OpenGL43, capabilities.OpenGL21);
        radialFog = capabilities.GL_NV_fog_distance;
        System.out.println("ICE_NATIVE_TERRAIN selected OpenGL " + (backend == 43 ? "4.3" : "2.1")
            + " backend; context=" + GL11.glGetString(GL11.GL_VERSION));
    }

    static NativeTerrainBackend create(int stride) {
        return CHOICE.get() == NativeTerrainBackendChoice.GL43
            ? new NativeTerrainGl43(stride) : new NativeTerrainGl21(stride);
    }

    static boolean radialFogSupported() { CHOICE.get(); return radialFog; }

    /** Check the remaining hardware/region guards after the scoped material bridge. */
    public static boolean requireTerrainVbo(boolean allowed) {
        CHOICE.get();
        if (!allowed) throw new IllegalStateException("ICE terrain VBOs were rejected by the original graphics guards. "
            + "Check hardware VBO support and OptiFine Render Regions support; no backend switch was attempted.");
        return true;
    }
}
