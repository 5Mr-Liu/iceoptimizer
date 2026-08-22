package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramState;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderFramebufferState;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import org.junit.Test;

public final class ModernRendererRuntimeShaderGateTest {
    @Test
    public void unknownAndActiveShaderPackStatesKeepFixedLayoutsOnLegacy() {
        ModernRendererRuntime runtime = new ModernRendererRuntime(
            new ClientEpochs(), new CacheBudget(1L, 1L, 1L));
        assertFalse(runtime.isShaderPackSafeForNativeVertexFormats());
        runtime.setShaderPackState(false, false);
        assertFalse(runtime.isShaderPackSafeForNativeVertexFormats());
        runtime.setShaderPackState(false, true);
        assertFalse(runtime.isShaderPackSafeForNativeVertexFormats());
        runtime.setShaderPackState(true, true);
        assertFalse(runtime.isShaderPackSafeForNativeVertexFormats());
        runtime.setShaderPackState(true, false);
        assertTrue(runtime.isShaderPackSafeForNativeVertexFormats());
    }

    @Test
    public void terrainShaderExceptionRequiresEveryIdentityAndCertificationGate() {
        assertTrue(ModernRendererRuntime.shaderTerrainGate(true, false,
            false, false, false, false, false, false, false));
        assertTrue(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, true, true, true, true, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(false, true,
            true, true, true, true, true, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            false, true, true, true, true, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, false, true, true, true, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, true, false, true, true, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, true, true, false, true, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, true, true, true, false, true, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, true, true, true, true, false, true));
        assertFalse(ModernRendererRuntime.shaderTerrainGate(true, true,
            true, true, true, true, true, true, false));
    }

    @Test
    public void terrainActivationRequiresCertifiedPassAndTrackedDrawFbo() {
        OptifineProgramState certified = state(17, 41, "GBUFFERS",
            new int[] { 36064, 36065 });
        OptifineProgramState candidate = state(117, 41, "GBUFFERS",
            new int[] { 36064, 36065 });
        assertTrue(ModernRendererRuntime.shaderTerrainActivationStateMatches(
            certified, candidate, 41));
        assertTrue("an exact recreated FBO layout remains the same logical pass",
            ModernRendererRuntime.shaderTerrainActivationStateMatches(
            certified, state(117, 42, "GBUFFERS",
                new int[] { 36064, 36065 }), 42));
        assertFalse(ModernRendererRuntime.shaderTerrainActivationStateMatches(
            certified, state(117, 41, "SHADOW",
                new int[] { 36064, 36065 }), 41));
        assertFalse(ModernRendererRuntime.shaderTerrainActivationStateMatches(
            certified, state(117, 41, "GBUFFERS",
                new int[] { 36064 }), 41));
        assertFalse(ModernRendererRuntime.shaderTerrainActivationStateMatches(
            certified, candidate, 99));
    }

    private static OptifineProgramState state(int program, int framebuffer,
                                               String stage,
                                               int[] drawBuffers) {
        return new OptifineProgramState("terrain", stage, program,
            framebuffer, new ShaderFramebufferState(128, 128, 0, 6402,
                new int[] { 6408, 6408 }), drawBuffers, 0, 0,
            null, null, null);
    }
}
