package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.compat.model.ModelStateBatchBridge;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderCounter;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ModelStateBatchAccountingTest {
    @Test
    public void completedScopesRecordEachPartOnceForEntityAndTesr() throws Exception {
        OptimizationModule[] modules = { OptimizationModule.MODERN_ENTITY_BACKEND,
            OptimizationModule.MODERN_TESR_BACKEND };
        for (OptimizationModule module : modules) {
            try (Fixture fixture = new Fixture(module, RenderThreadGuard.captureCurrent())) {
                fixture.openScope(7, 7);
                ModelStateBatchBridge.end();
                fixture.assertCounts(7, 7L);
                ModelStateBatchBridge.end();
                fixture.assertCounts(7, 7L);
                assertFalse(ModelStateBatchBridge.draw(19));
            }
        }
    }

    @Test
    public void fallbackClosesAndRecordsOnlyPreviouslySubmittedParts() throws Exception {
        try (Fixture fixture = new Fixture(OptimizationModule.MODERN_ENTITY_BACKEND,
            RenderThreadGuard.captureCurrent())) {
            fixture.openScope(7, 3);
            // The next preflighted list differs: this part must remain available
            // to the ordinary call-list path, while the three earlier draws count.
            int[] lists = (int[]) field(fixture.scope.getClass(), "displayLists")
                .get(fixture.scope);
            lists[3] = 91;
            assertFalse(ModelStateBatchBridge.draw(92));
            assertEquals(1L, fixture.cache.getStateBatchRejections());
            fixture.assertCounts(3, 3L);
            ModelStateBatchBridge.end();
            fixture.assertCounts(3, 3L);
        }
    }

    @Test
    public void cleanupFailureStillRecordsSuccessfulPartsAndClosesScope() throws Exception {
        try (Fixture fixture = new Fixture(OptimizationModule.MODERN_ENTITY_BACKEND,
            guardOwnedByAnotherThread())) {
            fixture.openScope(7, 3);
            try {
                ModelStateBatchBridge.end();
                fail("the original cleanup failure must escape");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("OpenGL lifecycle operation"));
            }
            fixture.assertCounts(3, 3L);
            ModelStateBatchBridge.end();
            fixture.assertCounts(3, 3L);
            assertFalse(ModelStateBatchBridge.draw(91));
        }
    }

    @Test
    public void failedRenderRetainsItsPrimaryExceptionAndRecordsCompletedParts()
        throws Exception {
        try (Fixture fixture = new Fixture(OptimizationModule.MODERN_TESR_BACKEND,
            guardOwnedByAnotherThread())) {
            fixture.openScope(6, 2);
            IllegalStateException primary = new IllegalStateException("render failed");
            ModelStateBatchBridge.endAfterFailure(primary);
            assertEquals("render failed", primary.getMessage());
            assertEquals(1, primary.getSuppressed().length);
            assertTrue(primary.getSuppressed()[0].getMessage()
                .contains("OpenGL lifecycle operation"));
            fixture.assertCounts(2, 2L);
            ModelStateBatchBridge.endAfterFailure(primary);
            assertEquals(1, primary.getSuppressed().length);
            fixture.assertCounts(2, 2L);
        }
    }

    @Test
    public void diagnosticSaturationDoesNotChangeTheProfilerDrawCount() throws Exception {
        try (Fixture fixture = new Fixture(OptimizationModule.MODERN_ENTITY_BACKEND,
            RenderThreadGuard.captureCurrent())) {
            fixture.drawReasons[fixture.backendIndex][fixture.modernDrawIndex] =
                Long.MAX_VALUE - 2L;
            fixture.openScope(7, 7);
            ModelStateBatchBridge.end();
            fixture.assertCounts(7, Long.MAX_VALUE);
        }
    }

    private static RenderThreadGuard guardOwnedByAnotherThread() throws Exception {
        final AtomicReference<RenderThreadGuard> captured =
            new AtomicReference<RenderThreadGuard>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() { captured.set(RenderThreadGuard.captureCurrent()); }
        }, "model-batch-cleanup-owner");
        worker.start();
        worker.join();
        return captured.get();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ThreadLocal<?> scopes() throws Exception {
        return (ThreadLocal<?>) field(ModelStateBatchBridge.class, "STATES").get(null);
    }

    private static ThreadLocal<?> traversals() throws Exception {
        return (ThreadLocal<?>) field(RenderLibRenderBridge.class, "TRAVERSALS").get(null);
    }

    private static final class Fixture implements AutoCloseable {
        private final CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(4);
        private final ModernRendererRuntime runtime;
        private final LwjglModelMeshCache cache;
        private final FrameStamp stamp;
        private final RenderPass pass;
        private final Object scope;
        private final Object traversal;
        private final long[][] drawReasons;
        private final int backendIndex;
        private final int modernDrawIndex;

        private Fixture(OptimizationModule module, RenderThreadGuard cacheGuard)
            throws Exception {
            scopes().remove();
            traversals().remove();
            field(ModelStateBatchBridge.class, "hasActiveBatch").setBoolean(null, false);
            ClientEpochs epochs = new ClientEpochs();
            CacheBudget budget = new CacheBudget(1024L, 1024L, 1024L);
            runtime = new ModernRendererRuntime(epochs, budget);
            stamp = new FrameStamp(1L, 1L, epochs.snapshot());
            pass = module == OptimizationModule.MODERN_TESR_BACKEND
                ? RenderPass.TESR_PASS_0 : RenderPass.ENTITY_PASS_0;
            field(ModernRendererRuntime.class, "currentStamp").set(runtime, stamp);
            runtime.components.profiler = profiler;
            ResourceLedger resources = new ResourceLedger(cacheGuard, budget,
                new ResourceLedger.Destroyer() {
                    @Override public void destroy(RenderResourceKind kind, int nativeId) { }
                }, 4);
            cache = new LwjglModelMeshCache(cacheGuard, resources, budget);
            scope = scopes().get();
            Object stack = traversals().get();
            traversal = ((Object[]) field(stack.getClass(), "values").get(stack))[0];
            field(stack.getClass(), "size").setInt(stack, 1);
            field(traversal.getClass(), "module").set(traversal, module);
            field(traversal.getClass(), "objectActive").setBoolean(traversal, true);
            field(traversal.getClass(), "objectPass").set(traversal, pass);
            drawReasons = (long[][]) field(ModernRendererRuntime.class, "modelDrawReasons")
                .get(runtime);
            backendIndex = module == OptimizationModule.MODERN_TESR_BACKEND ? 1 : 0;
            Class<?> reasonClass = Class.forName(ModernRendererRuntime.class.getName()
                + "$ModelDrawReason");
            int modernIndex = -1;
            for (Object reason : reasonClass.getEnumConstants()) {
                if ("MODERN_DRAW".equals(reason.toString())) {
                    modernIndex = ((Enum<?>) reason).ordinal();
                }
            }
            if (modernIndex < 0) throw new AssertionError("modern draw diagnostic unavailable");
            modernDrawIndex = modernIndex;
        }

        private void openScope(int admitted, int submitted) throws Exception {
            // Native submission equivalence is tested separately. Seed the
            // completed submissions to exercise actual close/fallback accounting
            // without requiring a GL context in the regression suite.
            field(scope.getClass(), "scopeOpened").setBoolean(scope, true);
            field(scope.getClass(), "active").setBoolean(scope, true);
            field(scope.getClass(), "cache").set(scope, cache);
            field(scope.getClass(), "runtime").set(scope, runtime);
            field(scope.getClass(), "stamp").set(scope, stamp);
            field(scope.getClass(), "count").setInt(scope, admitted);
            field(scope.getClass(), "next").setInt(scope, submitted);
            field(ModelStateBatchBridge.class, "hasActiveBatch").setBoolean(null, true);
        }

        private void assertCounts(int submitted, long diagnostic) throws Exception {
            assertEquals(submitted,
                field(traversal.getClass(), "eligibleDraws").getInt(traversal));
            assertEquals(submitted,
                field(traversal.getClass(), "modernDraws").getInt(traversal));
            assertEquals(diagnostic, drawReasons[backendIndex][modernDrawIndex]);
            assertEquals(0L, drawReasons[1 - backendIndex][modernDrawIndex]);
            assertEquals(Long.valueOf(submitted), profiler.snapshot().getProfiles().get(
                new RenderProfileKey(stamp, pass, RenderBackendId.ICE_NATIVE))
                .getCounters().get(RenderCounter.DRAW));
        }

        @Override public void close() throws Exception {
            scopes().remove();
            traversals().remove();
            field(ModelStateBatchBridge.class, "hasActiveBatch").setBoolean(null, false);
        }
    }
}
