package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.GpuTimestampDriver;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.visibility.ConservativeHzb;
import dev.rlcraft.ice.optimizer.render.visibility.ConservativeOcclusionHistory;
import dev.rlcraft.ice.optimizer.render.visibility.HzbHistoryKey;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglConservativeDepthReducer;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglDepthHistory;
import dev.rlcraft.ice.optimizer.render.visibility.RenderMatrixBridge;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import org.junit.Test;

public final class ModernRendererHzbReuseTest {
    @Test
    public void reusedModernIntervalPreservesCpuFirstGpuLaterSample() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.assertQueryStaysOpenUntilCutout();
            invoke(fixture.runtime, "addHzbCpuNanos",
                new Class<?>[] { RenderProfileKey.class, long.class }, fixture.key, 100L);
            fixture.runtime.afterTerrainLayer(fixture.container, BlockRenderLayer.CUTOUT);
            assertEquals("the reused interval must close its timestamp query",
                2, fixture.driver.timestamps);
            assertTrue("GPU result may arrive on a later frame", fixture.pending());
            assertEquals(1, fixture.backend.snapshot().getMeasurementSamples());
            assertEquals(0L, fixture.depth.getCaptures());
            assertEquals(1, fixture.profiler.pollGpu(4));
            assertFalse(fixture.pending());
            assertEquals("one legacy and one reused modern sample", 2,
                fixture.backend.snapshot().getMeasurementSamples());
            assertEquals(0, fixture.profiler.pollGpu(4));
            assertEquals(2, fixture.backend.snapshot().getMeasurementSamples());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void reusedModernIntervalAcceptsGpuFirstCpuLaterSample() throws Exception {
        Fixture fixture = new Fixture();
        try {
            invoke(fixture.runtime, "closeHzbGpuScope",
                new Class<?>[] { RenderProfileKey.class }, fixture.key);
            assertEquals(1, fixture.profiler.pollGpu(4));
            assertTrue("GPU completion alone cannot finalize CPU timing", fixture.pending());
            assertEquals(1, fixture.backend.snapshot().getMeasurementSamples());
            fixture.runtime.afterTerrainLayer(fixture.container, BlockRenderLayer.CUTOUT);
            assertFalse(fixture.pending());
            assertEquals(2, fixture.backend.snapshot().getMeasurementSamples());
            assertEquals(2, fixture.driver.timestamps);
            assertEquals(0L, fixture.depth.getCaptures());
            fixture.runtime.afterTerrainLayer(fixture.container, BlockRenderLayer.CUTOUT);
            assertEquals("the completion must be consumed once", 2,
                fixture.backend.snapshot().getMeasurementSamples());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void legacyIntervalMatchesModernCutoutBoundaryAndRetainsLateGpuResult()
        throws Exception {
        Fixture fixture = new Fixture(MeasurementArm.LEGACY, false);
        try {
            fixture.assertQueryStaysOpenUntilCutout();
            invoke(fixture.runtime, "addHzbCpuNanos",
                new Class<?>[] { RenderProfileKey.class, long.class }, fixture.key, 100L);
            fixture.runtime.afterTerrainLayer(fixture.container, BlockRenderLayer.CUTOUT);
            assertEquals(2, fixture.driver.timestamps);
            assertTrue(fixture.pending());
            assertEquals(0, fixture.backend.snapshot().getMeasurementSamples());
            assertEquals(1, fixture.profiler.pollGpu(4));
            assertFalse(fixture.pending());
            assertEquals(1, fixture.backend.snapshot().getMeasurementSamples());
            assertEquals(0L, fixture.depth.getCaptures());
            fixture.runtime.afterTerrainLayer(fixture.container, BlockRenderLayer.CUTOUT);
            assertEquals(1, fixture.backend.snapshot().getMeasurementSamples());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void legacyIntervalAcceptsGpuFirstCpuLaterSample() throws Exception {
        Fixture fixture = new Fixture(MeasurementArm.LEGACY, false);
        try {
            invoke(fixture.runtime, "closeHzbGpuScope",
                new Class<?>[] { RenderProfileKey.class }, fixture.key);
            assertEquals(1, fixture.profiler.pollGpu(4));
            assertTrue(fixture.pending());
            fixture.runtime.afterTerrainLayer(fixture.container, BlockRenderLayer.CUTOUT);
            assertFalse(fixture.pending());
            assertEquals(1, fixture.backend.snapshot().getMeasurementSamples());
            assertEquals(0L, fixture.depth.getCaptures());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void missingCutoutHookDiscardsEitherArmInsteadOfRecordingPartialInterval()
        throws Exception {
        for (MeasurementArm arm : MeasurementArm.values()) {
            Fixture fixture = new Fixture(arm, false);
            try {
                fixture.assertQueryStaysOpenUntilCutout();
                int before = fixture.backend.snapshot().getMeasurementSamples();
                invoke(fixture.runtime, "discardOpenHzbGpuSamples",
                    new Class<?>[] { long.class }, 10L);
                assertFalse(fixture.pending());
                assertEquals(2, fixture.driver.timestamps);
                assertEquals(1, fixture.profiler.pollGpu(4));
                assertEquals(before, fixture.backend.snapshot().getMeasurementSamples());
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    public void validationProgressRequiresIndependentOraclePublications() throws Exception {
        Fixture fixture = new Fixture(MeasurementArm.MODERN, true);
        try {
            // The retained warmup image has no source-depth oracle. Rechecking
            // its CPU pyramid in many frames must not certify the GPU reducer.
            for (long frame = 10L; frame < 15L; frame++) {
                field("currentStamp").set(fixture.runtime,
                    new FrameStamp(frame, 1L, fixture.epochs.snapshot()));
                assertFalse(fixture.runtime.tryRenderTerrain(fixture.container, BlockRenderLayer.SOLID));
                assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, fixture.backend.lifecycleState());
            }
            publishCpuDepth(fixture.depth, new FrameStamp(15L, 1L,
                fixture.epochs.snapshot()), true);
            field("currentStamp").set(fixture.runtime,
                new FrameStamp(16L, 1L, fixture.epochs.snapshot()));
            invoke(fixture.runtime, "validatePublishedHzbOnce", new Class<?>[0]);
            assertEquals(BackendLifecycleState.OUTPUT_VALIDATE, fixture.backend.lifecycleState());
            for (long frame = 17L; frame < 22L; frame++) {
                field("currentStamp").set(fixture.runtime,
                    new FrameStamp(frame, 1L, fixture.epochs.snapshot()));
                invoke(fixture.runtime, "validatePublishedHzbOnce", new Class<?>[0]);
                fixture.runtime.tryRenderTerrain(fixture.container, BlockRenderLayer.SOLID);
                assertEquals("reused oracle is consumed once",
                    BackendLifecycleState.OUTPUT_VALIDATE, fixture.backend.lifecycleState());
            }
            publishCpuDepth(fixture.depth, new FrameStamp(22L, 1L,
                fixture.epochs.snapshot()), true);
            field("currentStamp").set(fixture.runtime,
                new FrameStamp(23L, 1L, fixture.epochs.snapshot()));
            invoke(fixture.runtime, "validatePublishedHzbOnce", new Class<?>[0]);
            assertEquals(BackendLifecycleState.PAIRED_MEASURE, fixture.backend.lifecycleState());
        } finally {
            fixture.close();
        }
    }

    private static Object invoke(ModernRendererRuntime runtime, String name,
                                 Class<?>[] parameters, Object... arguments) throws Exception {
        Object owner = "validatePublishedHzbOnce".equals(name) ? runtime : runtime.measurements;
        Method method = owner.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(owner, arguments);
    }

    private static Field field(String name) throws Exception {
        Field field = ModernRendererRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field componentField(String name) throws Exception {
        Field field = RendererComponents.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field measurementField(String name) throws Exception {
        Field field = RenderMeasurementRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientEpochs epochs = new ClientEpochs();
        private final CacheBudget budget = new CacheBudget(4L << 20, 1L << 20, 1L << 20);
        private final ModernRendererRuntime runtime = new ModernRendererRuntime(epochs, budget);
        private final CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(4);
        private final Driver driver = new Driver();
        private final Container container = new Container();
        private final LwjglDepthHistory depth;
        private final LwjglTerrainArena terrain;
        private final AdaptiveBackendController backend;
        private final RenderProfileKey key;

        private Fixture() throws Exception {
            this(MeasurementArm.MODERN, false);
        }

        @SuppressWarnings("unchecked")
        private Fixture(MeasurementArm arm, boolean outputValidation) throws Exception {
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            ResourceLedger resources = new ResourceLedger(guard, budget,
                new ResourceLedger.Destroyer() {
                    @Override public void destroy(RenderResourceKind kind, int id) { }
                }, 4);
            depth = new LwjglDepthHistory(guard, resources,
                CapabilityReport.builder().pass(ModernCapability.BUFFER_OBJECT)
                    .pass(ModernCapability.SYNC_FENCE)
                    .pass(ModernCapability.OFFSCREEN_FRAMEBUFFER)
                    .pass(ModernCapability.CONSERVATIVE_HZB).build(),
                new ConservativeOcclusionHistory(), budget);
            terrain = new LwjglTerrainArena(guard, resources, budget,
                CapabilityReport.builder().build(), 16L << 20, 1L);
            float[] matrix = new float[16];
            matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1.0F;
            RenderMatrixBridge.capture(FloatBuffer.wrap(matrix), FloatBuffer.wrap(matrix),
                IntBuffer.wrap(new int[] { 0, 0, 64, 64 }));
            publishCpuDepth(depth, new FrameStamp(9L, 1L, epochs.snapshot()), false);
            FrameStamp stamp = new FrameStamp(10L, 1L, epochs.snapshot());
            field("initialized").setBoolean(runtime, true);
            field("currentStamp").set(runtime, stamp);
            componentField("threadGuard").set(runtime.components, guard);
            componentField("depthHistory").set(runtime.components, depth);
            componentField("terrainArena").set(runtime.components, terrain);
            runtime.shaderRuntime.shaderPackStateKnown = true;

            ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(
                OptimizationModule.MODERN_VISIBILITY_HZB);
            breaker.configure(true, 3);
            backend = new AdaptiveBackendController(breaker,
                EnumSet.noneOf(ModernCapability.class), 1,
                outputValidation ? 2 : 1, 1, 0.05D, 0.02D);
            backend.begin(1L);
            backend.capabilityResult(CapabilityReport.builder().build());
            backend.warmupFrame(true);
            if (!outputValidation) backend.validationResult(true, null);
            SceneFingerprint scene = new SceneFingerprint(0, 0, 0, 0, 0, 0,
                100, 20, 5, 12, 64, 64, 0, 1L, 1L);
            if (!outputValidation && arm == MeasurementArm.MODERN) {
                backend.recordMeasurement(scene, MeasurementArm.LEGACY, 1000000L, true);
            }
            EnumMap<OptimizationModule, AdaptiveBackendController> backends =
                (EnumMap<OptimizationModule, AdaptiveBackendController>) componentField("backends").get(runtime.components);
            backends.put(OptimizationModule.MODERN_VISIBILITY_HZB, backend);
            key = (RenderProfileKey) invoke(runtime, "beginHzbGpuSample",
                new Class<?>[] { SceneFingerprint.class, MeasurementArm.class },
                scene, arm);
            profiler.attachGpuQueries(driver, 4);
            CorrelatedRenderProfiler.GpuCompletion completion =
                (CorrelatedRenderProfiler.GpuCompletion) measurementField("hzbGpuCompletion").get(runtime.measurements);
            CorrelatedRenderProfiler.GpuScope scope = profiler.beginGpu(stamp,
                RenderPass.MAIN_SOLID, arm == MeasurementArm.MODERN
                    ? RenderBackendId.ICE_NATIVE : RenderBackendId.LEGACY, completion);
            invoke(runtime, "attachHzbGpuScope",
                new Class<?>[] { RenderProfileKey.class, CorrelatedRenderProfiler.GpuScope.class },
                key, scope);
            EarlyGlStateTracker.invalidate();
        }

        private void assertQueryStaysOpenUntilCutout() {
            runtime.afterTerrainLayer(container, BlockRenderLayer.SOLID);
            assertEquals(1, driver.timestamps);
            runtime.afterTerrainLayer(container, BlockRenderLayer.CUTOUT_MIPPED);
            assertEquals(1, driver.timestamps);
            assertEquals(0, profiler.pollGpu(4));
        }

        private boolean pending() throws Exception {
            return ((Map<?, ?>) measurementField("hzbGpuSamples").get(runtime.measurements)).containsKey(key);
        }

        @Override public void close() {
            profiler.resetGpu(false);
            depth.close(false);
            terrain.close(false);
            EarlyGlStateTracker.invalidate();
            assertEquals(0L, budget.snapshot().getHeapUsed());
        }
    }

    private static void publishCpuDepth(LwjglDepthHistory depth, FrameStamp stamp,
                                        boolean oracleValidated) throws Exception {
        Class<?> captureType = Class.forName(LwjglDepthHistory.class.getName() + "$Capture");
        Constructor<?> captureConstructor = captureType.getDeclaredConstructor(FrameStamp.class,
            RenderMatrixBridge.Snapshot.class, double.class, double.class, double.class,
            long.class, int.class, float.class, LwjglConservativeDepthReducer.Reduction.class,
            boolean.class);
        captureConstructor.setAccessible(true);
        Object capture = captureConstructor.newInstance(stamp, RenderMatrixBridge.snapshot(),
            0.0D, 0.0D, 0.0D, 1L, Integer.MIN_VALUE, 0.0F, null, oracleValidated);
        ConservativeHzb hzb = ConservativeHzb.buildStandardDepth(new float[] { 1, 1, 1, 1 }, 2, 2);
        HzbHistoryKey key = new HzbHistoryKey(Integer.MIN_VALUE, 2, 2, 0.0F,
            0L, 1L, 1L, RenderMatrixBridge.snapshot().getMatrixHash(), true);
        Class<?> frameType = Class.forName(LwjglDepthHistory.class.getName() + "$DepthFrame");
        Constructor<?> frameConstructor = frameType.getDeclaredConstructor(captureType,
            ConservativeHzb.class, HzbHistoryKey.class, int.class, int.class, int.class,
            boolean.class);
        frameConstructor.setAccessible(true);
        Object frame = frameConstructor.newInstance(capture, hzb, key, 32, 2, 2, oracleValidated);
        Method publish = LwjglDepthHistory.class.getDeclaredMethod("publishDepthFrame", frameType);
        publish.setAccessible(true);
        publish.invoke(depth, frame);
    }

    private static final class Container implements TerrainRenderListAccessor {
        private final List<RenderChunk> chunks = new ArrayList<RenderChunk>();
        @Override public List<RenderChunk> ice$renderChunks() { return chunks; }
        @Override public boolean ice$initialized() { return true; }
        @Override public double ice$viewEntityX() { return 0.0D; }
        @Override public double ice$viewEntityY() { return 0.0D; }
        @Override public double ice$viewEntityZ() { return 0.0D; }
    }

    private static final class Driver implements GpuTimestampDriver {
        private int next;
        private int timestamps;
        @Override public int createQuery() { return ++next; }
        @Override public void timestamp(int queryId) { timestamps++; }
        @Override public boolean isAvailable(int queryId) { return true; }
        @Override public long resultNanos(int queryId) { return queryId * 100L; }
        @Override public void deleteQuery(int queryId) { }
    }
}
