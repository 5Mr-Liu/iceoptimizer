package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleState;
import dev.rlcraft.ice.optimizer.ModuleStatus;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.lock.PackComponent;
import dev.rlcraft.ice.optimizer.lock.PackLockState;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import dev.rlcraft.ice.optimizer.runtime.RenderQueueStatus;
import dev.rlcraft.ice.optimizer.runtime.WorkerStatus;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkRenderStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class F3OptimizerSummaryTest {
    @Test
    public void rendersOnlyWhenConfiguredAndTheVanillaDebugScreenIsOpen() {
        assertFalse(F3OptimizerSummary.shouldRender(false, false));
        assertFalse(F3OptimizerSummary.shouldRender(false, true));
        assertFalse(F3OptimizerSummary.shouldRender(true, false));
        assertTrue(F3OptimizerSummary.shouldRender(true, true));
    }

    @Test
    public void formatsExactlyTwoCompactDebugLines() {
        List<ModuleStatus> modules = Arrays.asList(
            new ModuleStatus(OptimizationModule.CORE_RUNTIME, ModuleState.ACTIVE, 1L, 0L, 0L, 0, 0, ""),
            new ModuleStatus(OptimizationModule.SRP_STATIC_MESH, ModuleState.ACTIVE, 3L, 0L, 0L, 1, 1, ""),
            new ModuleStatus(OptimizationModule.LYCANITES_OBJ_RENDER, ModuleState.VERIFIED, 0L, 0L, 0L, 1, 1, ""),
            new ModuleStatus(OptimizationModule.CHUNK_MESH_AO, ModuleState.DEGRADED, 1L, 1L, 2L, 1, 1, "")
        );
        PackLockStatus lock = new PackLockStatus(PackLockState.VERIFIED, "",
            Collections.<PackComponent>emptyList(), null);
        ClientOptimizerStatus status = new ClientOptimizerStatus(true, true, lock,
            new WorkerStatus(4, 3, 1024, 5L, 2L, 0L, 0L),
            new RenderQueueStatus(2048, 7, 8L, 1L, 0L, 0L, 0L),
            null, null, new ChunkRenderStatus(16, 8, 32, 120L, 9L, 2L, "GPU-COPY"), modules);

        List<String> lines = F3OptimizerSummary.format(status);
        assertEquals(3, lines.size());
        assertEquals("ICE Opt: CORE OK | HIT 2 | PATCH 3 | ERR 3", lines.get(0));
        assertEquals("ICE Chunk: W 16>8 B32 | Sort 120 | GPU GPU-COPY 9/2", lines.get(1));
        assertEquals("ICE Q: CPU 3/1024 | Render 7/2048", lines.get(2));
    }
}
