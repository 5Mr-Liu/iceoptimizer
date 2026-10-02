package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;
import sun.misc.Unsafe;

/** Exercises the production filter, capture preflight and publication together. */
public final class LwjglDepthHistoryConfirmationTest {
    @Test
    public void hiddenChunkGetsSecondWitnessThenLongLivedReuse() throws Exception {
        Fixture fixture = new Fixture(false);
        try {
            fixture.primeCaptureCadence();
            fixture.publish(2L, 0.1F);
            RenderChunk chunk = chunk();
            fixture.container.chunks.add(chunk);
            assertEquals(0, fixture.filter(3L));
            assertEquals(1L, fixture.depth.getConfirmationDeferrals());
            // Repeating another layer or frame with publication 1 never counts
            // as independent evidence, and must not accidentally hide it.
            assertEquals(0, fixture.filter(3L));
            assertEquals(LwjglDepthHistory.CaptureOutcome.CAPTURE_READY,
                fixture.preflight(3L, false));
            fixture.depth.cancelPreflight();
            fixture.publish(3L, 0.1F);
            assertEquals(1, fixture.filter(4L));
            fixture.depth.commitFilter(fixture.container);
            assertTrue(fixture.container.chunks.isEmpty());

            for (long frame = 5L; frame < 55L; frame++) {
                fixture.container.chunks.add(chunk);
                assertEquals(1, fixture.filter(frame));
                fixture.depth.commitFilter(fixture.container);
                assertEquals(LwjglDepthHistory.CaptureOutcome.HISTORY_REUSED,
                    fixture.preflight(frame, false));
            }
            assertEquals("stationary confirmed geometry needs no new readback",
                2L, fixture.depth.getPublished());

            // A later chunk first seen in the reused image still needs its own
            // second witness; a global two-publication counter is insufficient.
            fixture.container.chunks.add(chunk());
            assertEquals(0, fixture.filter(55L));
            assertEquals(LwjglDepthHistory.CaptureOutcome.VIEW_UNSTABLE,
                fixture.preflight(55L, false));
            assertEquals(LwjglDepthHistory.CaptureOutcome.CAPTURE_READY,
                fixture.preflight(56L, false));
            fixture.depth.cancelPreflight();
            fixture.publish(56L, 0.1F);
            assertEquals(1, fixture.filter(57L));
            fixture.depth.commitFilter(fixture.container);
            assertEquals(LwjglDepthHistory.CaptureOutcome.HISTORY_REUSED,
                fixture.preflight(57L, false));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void newDepthThatRevealsChunkKeepsItAndClearsWitnesses() throws Exception {
        Fixture fixture = new Fixture(false);
        try {
            fixture.primeCaptureCadence();
            fixture.publish(2L, 0.1F);
            fixture.container.chunks.add(chunk());
            assertEquals(0, fixture.filter(3L));
            fixture.publish(3L, 1.0F);
            assertEquals(0, fixture.filter(4L));
            assertEquals(1, fixture.container.chunks.size());
            assertEquals(LwjglDepthHistory.CaptureOutcome.HISTORY_REUSED,
                fixture.preflight(4L, false));

            fixture.publish(4L, 0.1F);
            assertEquals("visibility in the preceding publication resets proof",
                0, fixture.filter(5L));
            fixture.publish(5L, 0.1F);
            assertEquals(1, fixture.filter(6L));
            fixture.depth.commitFilter(fixture.container);
        } finally {
            fixture.close();
        }
    }

    @Test
    public void exhaustedWitnessBudgetDoesNotCauseRepeatedReadbacks() throws Exception {
        Fixture fixture = new Fixture(true);
        try {
            assertEquals(0, fixture.depth.getOcclusionGateCapacity());
            fixture.publish(2L, 0.1F);
            fixture.container.chunks.add(chunk());
            for (long frame = 3L; frame < 20L; frame++) {
                assertEquals(0, fixture.filter(frame));
                assertEquals(LwjglDepthHistory.CaptureOutcome.HISTORY_REUSED,
                    fixture.preflight(frame, false));
            }
            assertTrue(fixture.depth.getRawOccluded() > 0L);
            assertEquals(1, fixture.container.chunks.size());
            assertEquals(1L, fixture.depth.getPublished());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void validationCanRefreshWithoutRepeatingTheViewGate() throws Exception {
        Fixture fixture = new Fixture(false);
        try {
            fixture.primeCaptureCadence();
            fixture.publish(2L, 0.1F);
            assertEquals(LwjglDepthHistory.CaptureOutcome.HISTORY_REUSED,
                fixture.preflight(3L, false));
            assertEquals(LwjglDepthHistory.CaptureOutcome.CAPTURE_READY,
                fixture.preflight(3L, true));
            // Stop at the software-mirror check, before any native GL command.
            // A duplicate view-gate observation here would reject this capture.
            EarlyGlStateTracker.invalidate();
            assertEquals(LwjglDepthHistory.CaptureOutcome.TEMPORARILY_UNAVAILABLE,
                fixture.depth.capturePreflighted(fixture.stamp(3L),
                    0.0D, 0.0D, 0.0D, true, true));
            assertEquals(0L, fixture.depth.getViewGateDuplicateFrame());
            assertEquals(LwjglDepthHistory.CaptureOutcome.VIEW_UNSTABLE,
                fixture.depth.capturePreflighted(fixture.stamp(3L),
                    0.0D, 0.0D, 0.0D, true, true));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void refreshModeMustMatchTheOneUseCaptureAuthorization() throws Exception {
        Fixture fixture = new Fixture(false);
        try {
            fixture.primeCaptureCadence();
            fixture.publish(2L, 0.1F);
            fixture.container.chunks.add(chunk());
            assertEquals(0, fixture.filter(3L));
            assertEquals(LwjglDepthHistory.CaptureOutcome.CAPTURE_READY,
                fixture.preflight(3L, false));
            assertEquals(LwjglDepthHistory.CaptureOutcome.VIEW_UNSTABLE,
                fixture.depth.capturePreflighted(fixture.stamp(3L),
                    0.0D, 0.0D, 0.0D, true, true));
            assertEquals(LwjglDepthHistory.CaptureOutcome.VIEW_UNSTABLE,
                fixture.depth.capturePreflighted(fixture.stamp(3L),
                    0.0D, 0.0D, 0.0D, false, false));
            assertEquals(0L, fixture.depth.getCaptures());
            assertFalse(fixture.depth.hasPendingReadback());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void liveValidationDoesNotTreatUnavailableViewsAsInvalidOutput() throws Exception {
        Fixture fixture = new Fixture(false);
        try {
            assertNull(fixture.depth.validateHistory(fixture.stamp(3L), fixture.container));
            fixture.publish(2L, 0.1F);
            fixture.container.chunks.add(chunk());
            assertEquals(Boolean.TRUE,
                fixture.depth.validateHistory(fixture.stamp(3L), fixture.container));
            fixture.container.viewX = 1.0D;
            assertNull(fixture.depth.validateHistory(fixture.stamp(3L), fixture.container));
            fixture.container.viewX = 0.0D;
            assertEquals(Boolean.TRUE,
                fixture.depth.validateHistory(fixture.stamp(4L), fixture.container));
            fixture.epochs.invalidateResources();
            assertNull(fixture.depth.validateHistory(fixture.stamp(4L), fixture.container));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void hierarchyValidationRejectsBadPublicationAndClosedImages() throws Exception {
        Fixture fixture = new Fixture(false);
        try {
            fixture.publish(2L, 0.1F, true);
            assertEquals(Boolean.FALSE,
                fixture.depth.validateHistory(fixture.stamp(3L), fixture.container));
            ConservativeHzb image = fixture.publish(3L, 0.1F, false);
            assertEquals(Boolean.TRUE,
                fixture.depth.validateHistory(fixture.stamp(4L), fixture.container));
            image.close();
            assertEquals(Boolean.FALSE,
                fixture.depth.validateHistory(fixture.stamp(4L), fixture.container));
        } finally {
            fixture.close();
        }
    }

    private static RenderChunk chunk() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (RenderChunk) unsafe.allocateInstance(TestChunk.class);
    }

    private static final class TestChunk extends RenderChunk {
        private TestChunk() { super(null, null, 0); }
        @Override public BlockPos getPosition() { return BlockPos.ORIGIN; }
    }

    private static final class Container implements TerrainRenderListAccessor {
        private final ArrayList<RenderChunk> chunks = new ArrayList<RenderChunk>();
        private double viewX;
        @Override public List<RenderChunk> ice$renderChunks() { return chunks; }
        @Override public boolean ice$initialized() { return true; }
        @Override public double ice$viewEntityX() { return viewX; }
        @Override public double ice$viewEntityY() { return 0.0D; }
        @Override public double ice$viewEntityZ() { return 0.0D; }
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientEpochs epochs = new ClientEpochs();
        private final CacheBudget budget = new CacheBudget(4L << 20, 1L << 20, 1L << 20);
        private final Container container = new Container();
        private final LwjglDepthHistory depth;

        private Fixture(boolean exhaustWitnessBudget) {
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            ResourceLedger ledger = new ResourceLedger(guard, budget,
                new ResourceLedger.Destroyer() {
                    @Override public void destroy(RenderResourceKind kind,
                                                  int nativeId) { }
                }, 4);
            CacheBudget.Reservation pressure = exhaustWitnessBudget
                ? budget.tryReserve(BudgetKind.HEAP, 4L << 20) : null;
            try {
                depth = new LwjglDepthHistory(guard, ledger,
                    CapabilityReport.builder().pass(ModernCapability.BUFFER_OBJECT)
                        .pass(ModernCapability.SYNC_FENCE)
                        .pass(ModernCapability.OFFSCREEN_FRAMEBUFFER)
                        .pass(ModernCapability.CONSERVATIVE_HZB).build(),
                    new ConservativeOcclusionHistory(), budget);
            } finally {
                if (pressure != null) pressure.close();
            }
            float[] model = new float[16];
            model[0] = model[5] = model[10] = model[15] = 1.0F;
            float[] projection = model.clone();
            projection[0] = projection[5] = projection[10] = 0.01F;
            RenderMatrixBridge.capture(FloatBuffer.wrap(model),
                FloatBuffer.wrap(projection), IntBuffer.wrap(new int[] { 0, 0, 64, 64 }));
        }

        private FrameStamp stamp(long frame) {
            return new FrameStamp(frame, 1L, epochs.snapshot());
        }

        private LwjglDepthHistory.CaptureOutcome preflight(long frame, boolean refresh) {
            return depth.preflightCapture(stamp(frame), 0.0D, 0.0D, 0.0D, refresh);
        }

        private void primeCaptureCadence() {
            assertEquals(LwjglDepthHistory.CaptureOutcome.VIEW_UNSTABLE,
                preflight(1L, false));
            assertEquals(LwjglDepthHistory.CaptureOutcome.CAPTURE_READY,
                preflight(2L, false));
            depth.cancelPreflight();
        }

        private int filter(long frame) {
            return depth.filter(container, BlockRenderLayer.SOLID, stamp(frame));
        }

        private void publish(long capturedFrame, float depthValue) throws Exception {
            publish(capturedFrame, depthValue, false);
        }

        private ConservativeHzb publish(long capturedFrame, float depthValue,
                                        boolean corruptHierarchy) throws Exception {
            // Inject only the completed CPU reduction. The real production
            // publication assigns serials, owns the hierarchy, and resets
            // per-image refresh requests; GL mapping/fences are not simulated.
            Class<?> captureType = Class.forName(LwjglDepthHistory.class.getName() + "$Capture");
            Constructor<?> captureConstructor = captureType.getDeclaredConstructor(
                FrameStamp.class, RenderMatrixBridge.Snapshot.class,
                double.class, double.class, double.class, long.class, int.class,
                float.class, LwjglConservativeDepthReducer.Reduction.class, boolean.class);
            captureConstructor.setAccessible(true);
            Field serial = LwjglDepthHistory.class.getDeclaredField("sceneSerial");
            serial.setAccessible(true);
            Object capture = captureConstructor.newInstance(stamp(capturedFrame),
                RenderMatrixBridge.snapshot(), 0.0D, 0.0D, 0.0D,
                serial.getLong(depth), Integer.MIN_VALUE, 0.0F, null, false);
            float[] pixels = new float[16 * 16];
            Arrays.fill(pixels, depthValue);
            ConservativeHzb hzb = ConservativeHzb.buildStandardDepth(
                FloatBuffer.wrap(pixels), 16, 16, budget);
            if (corruptHierarchy) {
                Field levels = ConservativeHzb.class.getDeclaredField("levels");
                levels.setAccessible(true);
                ((float[][]) levels.get(hzb))[1][0] = 0.0F;
            }
            HzbHistoryKey key = new HzbHistoryKey(Integer.MIN_VALUE, 16, 16,
                0.0F, 0L, 1L, 1L, RenderMatrixBridge.snapshot().getMatrixHash(), true);
            Class<?> frameType = Class.forName(LwjglDepthHistory.class.getName() + "$DepthFrame");
            Constructor<?> frameConstructor = frameType.getDeclaredConstructor(
                captureType, ConservativeHzb.class, HzbHistoryKey.class,
                int.class, int.class, int.class, boolean.class);
            frameConstructor.setAccessible(true);
            Object frame = frameConstructor.newInstance(capture, hzb, key, 4, 16, 16, false);
            Method publish = LwjglDepthHistory.class.getDeclaredMethod("publishDepthFrame", frameType);
            publish.setAccessible(true);
            publish.invoke(depth, frame);
            return hzb;
        }

        @Override public void close() {
            depth.close(false);
            EarlyGlStateTracker.invalidate();
            assertEquals("all retained witness and hierarchy bytes are released",
                0L, budget.snapshot().getHeapUsed());
        }
    }
}
