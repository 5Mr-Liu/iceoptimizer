package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.util.BlockRenderLayer;
import org.junit.Test;

public final class ModernRendererRuntimeTerrainPolicyTest {
    @Test
    public void shadowAdoptionIsBoundedPerFrame() {
        assertTrue(ModernRendererRuntime.terrainShadowBudgetAllows(0, 0L, 1));
        assertTrue(ModernRendererRuntime.terrainShadowBudgetAllows(7,
            15L * 1024L * 1024L, 1024 * 1024));
        assertFalse(ModernRendererRuntime.terrainShadowBudgetAllows(8, 0L, 1));
        assertFalse(ModernRendererRuntime.terrainShadowBudgetAllows(0,
            16L * 1024L * 1024L, 1));
        assertFalse(ModernRendererRuntime.terrainShadowBudgetAllows(0, 0L, 0));
    }

    @Test
    public void pairedMeasurementRejectsSparseMixedOwnership() {
        assertFalse(ModernRendererRuntime.terrainMeasurementCoverageStable(
            1000, 3));
        assertFalse(ModernRendererRuntime.terrainMeasurementCoverageStable(
            100, 59));
        assertTrue(ModernRendererRuntime.terrainMeasurementCoverageStable(
            100, 64));
        assertTrue(ModernRendererRuntime.terrainMeasurementCoverageStable(
            64, 64));
    }

    @Test
    public void qualificationReservesTheBoundedShadowArenaForSolid() {
        assertTrue(ModernRendererRuntime.terrainShadowQualificationAllowed(
            BlockRenderLayer.SOLID, false));
        assertFalse(ModernRendererRuntime.terrainShadowQualificationAllowed(
            BlockRenderLayer.CUTOUT, false));
        assertFalse(ModernRendererRuntime.terrainShadowQualificationAllowed(
            BlockRenderLayer.TRANSLUCENT, false));
        assertFalse(ModernRendererRuntime.terrainShadowQualificationAllowed(
            null, false));
    }

    @Test
    public void shaderCertificationMayRetainEveryConcreteLayerTwin() {
        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            assertTrue(ModernRendererRuntime.terrainShadowQualificationAllowed(
                layer, true));
        }
        assertFalse(ModernRendererRuntime.terrainShadowQualificationAllowed(
            null, true));
    }
}
