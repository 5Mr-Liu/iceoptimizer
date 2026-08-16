package dev.rlcraft.ice.optimizer.compat.chunk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ChunkRenderPolicyBridgeTest {
    @Test
    public void reservesMainThreadCapacityAcrossCpuSizes() {
        assertEquals(1, ChunkRenderPolicyBridge.computeWorkerCount(1, 16));
        assertEquals(1, ChunkRenderPolicyBridge.computeWorkerCount(2, 2));
        assertEquals(3, ChunkRenderPolicyBridge.computeWorkerCount(4, 4));
        assertEquals(6, ChunkRenderPolicyBridge.computeWorkerCount(8, 8));
        assertEquals(8, ChunkRenderPolicyBridge.computeWorkerCount(16, 16));
        assertEquals(12, ChunkRenderPolicyBridge.computeWorkerCount(32, 24));
        assertEquals(16, ChunkRenderPolicyBridge.computeWorkerCount(32, 32));
        assertEquals(16, ChunkRenderPolicyBridge.computeWorkerCount(64, 64));
        assertEquals(6, ChunkRenderPolicyBridge.computeWorkerCount(6, 32));
    }

    @Test
    public void limitsWorkersByAvailableJvmHeapInsteadOfAssumingOnePcSize() {
        long mib = 1024L * 1024L;
        assertEquals(2, ChunkRenderPolicyBridge.computeWorkerCount(32, 32, 1024L * mib));
        assertEquals(4, ChunkRenderPolicyBridge.computeWorkerCount(32, 32, 2048L * mib));
        assertEquals(6, ChunkRenderPolicyBridge.computeWorkerCount(32, 32, 3072L * mib));
        assertEquals(8, ChunkRenderPolicyBridge.computeWorkerCount(32, 32, 4096L * mib));
        assertEquals(12, ChunkRenderPolicyBridge.computeWorkerCount(32, 32, 6144L * mib));
        assertEquals(16, ChunkRenderPolicyBridge.computeWorkerCount(32, 32, 8192L * mib));
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

    @Test
    public void gpuCopyAcceptsCoreAndArbDriverPathsIndependently() {
        assertEquals("GL31-COPY",
            ChunkVboUploadBridge.backendForTest(true, false, true, false));
        assertEquals("GL31-COPY",
            ChunkVboUploadBridge.backendForTest(true, false, false, true));
        assertEquals("ARB-COPY",
            ChunkVboUploadBridge.backendForTest(false, true, false, true));
        assertEquals("UNSUPPORTED",
            ChunkVboUploadBridge.backendForTest(false, false, true, true));
        assertEquals("UNSUPPORTED",
            ChunkVboUploadBridge.backendForTest(true, true, false, false));
    }
}
