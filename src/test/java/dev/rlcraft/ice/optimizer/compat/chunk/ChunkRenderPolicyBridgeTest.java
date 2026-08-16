package dev.rlcraft.ice.optimizer.compat.chunk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ChunkRenderPolicyBridgeTest {
    @Test
    public void reservesMainThreadCapacityAcrossCpuSizes() {
        assertEquals(1, ChunkRenderPolicyBridge.computeWorkerCount(1, 16));
        assertEquals(2, ChunkRenderPolicyBridge.computeWorkerCount(2, 2));
        assertEquals(3, ChunkRenderPolicyBridge.computeWorkerCount(4, 4));
        assertEquals(6, ChunkRenderPolicyBridge.computeWorkerCount(8, 8));
        assertEquals(8, ChunkRenderPolicyBridge.computeWorkerCount(16, 16));
        assertEquals(6, ChunkRenderPolicyBridge.computeWorkerCount(6, 32));
    }

    @Test
    public void builderPoolKeepsFourPipelineSlotsPerWorkerWithoutGrowingVanilla() {
        assertEquals(32, ChunkRenderPolicyBridge.computeBuilderCount(160, 8));
        assertEquals(12, ChunkRenderPolicyBridge.computeBuilderCount(12, 8));
        assertEquals(4, ChunkRenderPolicyBridge.computeBuilderCount(40, 1));
    }

    @Test
    public void stagingCapacityIsPowerOfTwoAndStrictlyBounded() {
        assertEquals(256 * 1024, ChunkVboUploadBridge.roundedCapacityForTest(1));
        assertEquals(512 * 1024, ChunkVboUploadBridge.roundedCapacityForTest(300 * 1024));
        assertEquals(16 * 1024 * 1024,
            ChunkVboUploadBridge.roundedCapacityForTest(16 * 1024 * 1024));
    }
}
