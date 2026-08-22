package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.CpuWorkKind;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.util.BlockRenderLayer;
import org.junit.After;
import org.junit.Test;

public final class ModernRendererTerrainDiagnosticsTest {
    @After
    public void reset() {
        ModernRendererDiagnostics.resetForTest();
    }

    @Test
    public void recordsCompletedLegacyDrawAgainstItsExactDeclineReason()
        throws Exception {
        ModernRendererRuntime runtime = runtime();

        runtime.terrainLegacyUpload();
        runtime.tryRenderTerrain(this, BlockRenderLayer.SOLID);
        runtime.afterLegacyTerrainLayer(this, BlockRenderLayer.SOLID);
        publish(runtime);

        String report = ModernRendererDiagnostics.report();
        assertTrue(report.contains("terrain_upload_legacy=1\n"));
        assertTrue(report.contains("terrain_draw_legacy=1\n"));
        assertTrue(report.contains("terrain_reason.RUNTIME_NOT_READY=1\n"));
        assertTrue(ModernRendererDiagnostics.summary().contains("D A/L 0/1"));
    }

    @Test
    public void separatesUnbatchedMultiDrawMdiAndUncertainArenaOutcomes()
        throws Exception {
        ModernRendererRuntime runtime = runtime();
        Method draw = ModernRendererRuntime.class.getDeclaredMethod(
            "recordTerrainArenaDraw", boolean.class, int.class);
        Method uncertain = ModernRendererRuntime.class.getDeclaredMethod(
            "recordTerrainArenaUncertainDraw");
        draw.setAccessible(true);
        uncertain.setAccessible(true);

        draw.invoke(runtime, false, 0);
        draw.invoke(runtime, true, 0);
        draw.invoke(runtime, true, 17);
        uncertain.invoke(runtime);
        publish(runtime);

        String report = ModernRendererDiagnostics.report();
        assertTrue(report.contains("terrain_draw_arena_total=4\n"));
        assertTrue(report.contains("terrain_draw_arena_unbatched=1\n"));
        assertTrue(report.contains("terrain_draw_arena_multi=1\n"));
        assertTrue(report.contains("terrain_draw_arena_uncertain=1\n"));
        assertTrue(report.contains("terrain_mdi_submissions=1\n"));
        assertTrue(report.contains("terrain_indirect_commands=17\n"));
    }

    @Test
    public void publishesStructuredOutcomeForEveryCapability() throws Exception {
        ModernRendererRuntime runtime = runtime();
        CapabilityReport capabilities = CapabilityReport.builder()
            .failUnreported(new CapabilityReport.FailureDetail("context",
                IllegalStateException.class.getName(), "context unavailable",
                "not_captured", "not_queried"))
            .build();
        Field field = ModernRendererRuntime.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        field.set(runtime, capabilities);

        publish(runtime);

        String report = ModernRendererDiagnostics.report();
        for (ModernCapability capability : ModernCapability.values()) {
            String prefix = "capability." + capability.name() + ".";
            assertTrue(prefix, report.contains(prefix + "status=FAIL\n"));
            assertTrue(prefix, report.contains(prefix + "stage=context\n"));
            assertTrue(prefix, report.contains(prefix + "exception="
                + IllegalStateException.class.getName() + "\n"));
            assertTrue(prefix, report.contains(prefix
                + "gl_state=not_captured\n"));
            assertTrue(prefix, report.contains(prefix
                + "gl_errors=not_queried\n"));
        }
    }

    @Test
    public void particleLinkageFailurePublishesTheMissingClassName()
        throws Exception {
        ModernRendererRuntime runtime = runtime();
        runtime.particleBackendFailure(new NoClassDefFoundError(
            "optional/particle/Dependency"));
        publish(runtime);

        String report = ModernRendererDiagnostics.report();
        assertTrue(report.contains("particle_backend_failures=1\n"));
        assertTrue(report.contains("particle_last_failure_exception="
            + "java.lang.NoClassDefFoundError\n"));
        assertTrue(report.contains("particle_last_failure_message="
            + "optional/particle/Dependency\n"));
        assertTrue(report.contains("particle_last_root_failure_exception="
            + "java.lang.NoClassDefFoundError\n"));
    }

    @Test
    public void publishesBudgetsLedgerAndPerBackendCpuGpuAttribution()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        CacheBudget budget = new CacheBudget(4096L, 8192L, 16384L);
        ModernRendererRuntime runtime = new ModernRendererRuntime(epochs, budget);
        CacheBudget.Reservation heap = budget.tryReserve(BudgetKind.HEAP, 256L);
        CacheBudget.Reservation direct = budget.tryReserve(BudgetKind.DIRECT, 512L);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                              int nativeId) { }
            }, 8);
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        ledger.register(RenderResourceKind.BUFFER, 7, 1024L, stamp);
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(8);
        for (RenderBackendId backend : RenderBackendId.values()) {
            CorrelatedRenderProfiler.CpuScope scope = profiler.beginCpu(stamp,
                RenderPass.MAIN_SOLID, backend, CpuWorkKind.WORK);
            scope.close();
        }
        setField(runtime, "resources", ledger);
        setField(runtime, "profiler", profiler);

        try {
            publish(runtime);
            String report = ModernRendererDiagnostics.report();
            assertTrue(report.contains(
                "optimizer_budget_heap_used_bytes=256\n"));
            assertTrue(report.contains(
                "optimizer_budget_direct_used_bytes=512\n"));
            assertTrue(report.contains(
                "optimizer_budget_gpu_used_bytes=1024\n"));
            assertTrue(report.contains("resource_ledger_live=1\n"));
            assertTrue(report.contains("resource_ledger_live_bytes=1024\n"));
            assertTrue(report.contains("resource_ledger_created=1\n"));
            for (RenderBackendId backend : RenderBackendId.values()) {
                String prefix = "render_profile." + backend.name() + ".";
                assertTrue(prefix, report.contains(prefix
                    + "profile_keys=1\n"));
                assertTrue(prefix, report.contains(prefix
                    + "gpu_nanos=0\n"));
                assertTrue(prefix, report.contains(prefix
                    + "gpu_profile_keys=0\n"));
            }
        } finally {
            ledger.destroyAll(1L);
            if (direct != null) direct.close();
            if (heap != null) heap.close();
        }
    }

    private static ModernRendererRuntime runtime() {
        return new ModernRendererRuntime(new ClientEpochs(),
            new CacheBudget(1L, 1L, 1L));
    }

    private static void publish(ModernRendererRuntime runtime) throws Exception {
        Method publish = ModernRendererRuntime.class.getDeclaredMethod(
            "publishRendererDiagnostics", boolean.class);
        publish.setAccessible(true);
        publish.invoke(runtime, true);
    }

    private static void setField(ModernRendererRuntime runtime, String name,
                                 Object value) throws Exception {
        Field field = ModernRendererRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }
}
