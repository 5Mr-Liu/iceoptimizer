package dev.rlcraft.ice.optimizer.compat.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class EarlyGlStateTrackerTest {
    @Test
    public void publishesOnlyCompleteSnapshotsAndInvalidatesAtomically() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(7);
        EarlyGlStateTracker.bindFramebuffer(36160, 8);
        EarlyGlStateTracker.depthFunction(515);
        assertNull(EarlyGlStateTracker.snapshot());
        EarlyGlStateTracker.bindBuffer(35051, 9);
        EarlyGlStateTracker.Snapshot snapshot = EarlyGlStateTracker.snapshot();
        assertNotNull(snapshot);
        assertEquals(7, snapshot.getProgram());
        assertEquals(8, snapshot.getReadFramebuffer());
        assertEquals(8, snapshot.getDrawFramebuffer());
        assertEquals(515, snapshot.getDepthFunction());
        assertEquals(9, snapshot.getPixelPackBuffer());

        long serial = snapshot.getInvalidationSerial();
        EarlyGlStateTracker.invalidate();
        assertFalse(EarlyGlStateTracker.isKnown());
        assertNull(EarlyGlStateTracker.snapshot());
        assertTrue(EarlyGlStateTracker.invalidations() > serial);
    }

    @Test
    public void stateIsStrictlyRenderThreadLocal() throws Exception {
        EarlyGlStateTracker.beginProbe();
        publishComplete(1, 2, 3);
        assertTrue(EarlyGlStateTracker.isKnown());
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    assertNull(EarlyGlStateTracker.snapshot());
                    publishComplete(11, 12, 13);
                    assertEquals(11, EarlyGlStateTracker.snapshot().getProgram());
                    EarlyGlStateTracker.invalidate();
                    assertNull(EarlyGlStateTracker.snapshot());
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        }, "ice-gl-state-test");
        worker.start();
        worker.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, EarlyGlStateTracker.snapshot().getProgram());
    }

    @Test
    public void genericFramebufferAndSplitBindingsHaveExactSemantics() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.depthFunction(513);
        EarlyGlStateTracker.bindBuffer(35051, 0);
        EarlyGlStateTracker.bindFramebuffer(36008, 21);
        assertNull(EarlyGlStateTracker.snapshot());
        EarlyGlStateTracker.bindFramebuffer(36009, 22);
        EarlyGlStateTracker.Snapshot split = EarlyGlStateTracker.snapshot();
        assertEquals(21, split.getReadFramebuffer());
        assertEquals(22, split.getDrawFramebuffer());
        EarlyGlStateTracker.bindFramebuffer(36160, 30);
        assertEquals(30, EarlyGlStateTracker.snapshot().getReadFramebuffer());
        assertEquals(30, EarlyGlStateTracker.snapshot().getDrawFramebuffer());
    }

    @Test
    public void drawStateRequiresCompleteSeedAndTracksWrapperSemantics() {
        EarlyGlStateTracker.beginProbe();
        publishComplete(3, 4, 5);
        EarlyGlStateTracker.bindBuffer(34962, 6);
        assertEquals(6, EarlyGlStateTracker.arrayBufferBinding());
        assertFalse(EarlyGlStateTracker.snapshot().hasDrawState());

        EarlyGlStateTracker.seedDrawState(1, 7, 8, true,
            770, 771, 1, 771, true, false, true, 5,
            0.25F, 0.5F, 0.75F, 0.9F);
        EarlyGlStateTracker.clientActiveTexture(33984);
        EarlyGlStateTracker.Snapshot seeded = EarlyGlStateTracker.snapshot();
        assertTrue(seeded.hasDrawState());
        assertEquals(1, seeded.getActiveTexture());
        assertEquals(7, seeded.getTexture0());
        assertEquals(8, seeded.getTexture1());
        assertEquals(0, seeded.getClientActiveTexture());
        assertTrue(seeded.isBlend());
        assertEquals(770, seeded.getBlendSourceRgb());
        assertEquals(771, seeded.getBlendDestinationRgb());
        assertEquals(1, seeded.getBlendSourceAlpha());
        assertEquals(771, seeded.getBlendDestinationAlpha());
        assertTrue(seeded.isDepthTest());
        assertFalse(seeded.isDepthMask());
        assertTrue(seeded.isCull());
        assertEquals(5, seeded.getColorMask());
        assertEquals(0.25F, seeded.getRed(), 0.0F);
        assertEquals(0.9F, seeded.getAlpha(), 0.0F);

        EarlyGlStateTracker.activeTexture(33984);
        EarlyGlStateTracker.bindTexture(9);
        EarlyGlStateTracker.activeTexture(33985);
        assertEquals(9, EarlyGlStateTracker.boundTextureForUnit(0));
        assertEquals(8, EarlyGlStateTracker.boundTextureForActiveUnit());
        EarlyGlStateTracker.activeTexture(33984);
        EarlyGlStateTracker.blendEnabled(false);
        EarlyGlStateTracker.blendFunction(1, 0);
        EarlyGlStateTracker.depthEnabled(false);
        EarlyGlStateTracker.depthMask(true);
        EarlyGlStateTracker.cullEnabled(false);
        EarlyGlStateTracker.colorMask(true, true, true, true);
        EarlyGlStateTracker.color(0.1F, 0.2F, 0.3F);
        EarlyGlStateTracker.Snapshot updated = EarlyGlStateTracker.snapshot();
        assertEquals(0, updated.getActiveTexture());
        assertEquals(9, updated.getTexture0());
        assertFalse(updated.isBlend());
        assertEquals(1, updated.getBlendSourceRgb());
        assertEquals(0, updated.getBlendDestinationRgb());
        assertFalse(updated.isDepthTest());
        assertTrue(updated.isDepthMask());
        assertFalse(updated.isCull());
        assertEquals(15, updated.getColorMask());
        assertEquals(1.0F, updated.getAlpha(), 0.0F);
    }

    @Test
    public void modelDrawGateIsNonAllocatingAndFailsClosed() {
        EarlyGlStateTracker.beginProbe();
        publishComplete(0, 4, 5);
        assertFalse(EarlyGlStateTracker.hasModelDrawState());
        assertEquals(Integer.MIN_VALUE,
            EarlyGlStateTracker.fixedFunctionModelArrayBufferBinding());

        EarlyGlStateTracker.bindBuffer(34962, 17);
        EarlyGlStateTracker.seedDrawState(0, 7, 8, false,
            1, 0, 1, 0, true, true, true, 15,
            1.0F, 1.0F, 1.0F, 1.0F);
        EarlyGlStateTracker.clientActiveTexture(33984);
        assertTrue(EarlyGlStateTracker.hasModelDrawState());
        assertEquals(17,
            EarlyGlStateTracker.fixedFunctionModelArrayBufferBinding());

        EarlyGlStateTracker.useProgram(3);
        assertTrue(EarlyGlStateTracker.hasModelDrawState());
        assertEquals(Integer.MIN_VALUE,
            EarlyGlStateTracker.fixedFunctionModelArrayBufferBinding());
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.clientActiveTexture(33985);
        assertEquals(Integer.MIN_VALUE,
            EarlyGlStateTracker.fixedFunctionModelArrayBufferBinding());
        EarlyGlStateTracker.invalidate();
        assertFalse(EarlyGlStateTracker.hasModelDrawState());
    }

    @Test
    public void hudStateRequiresTextureEnableAndViewportAndTracksChanges() {
        EarlyGlStateTracker.beginProbe();
        publishComplete(0, 4, 0);
        EarlyGlStateTracker.bindBuffer(34962, 0);
        EarlyGlStateTracker.seedDrawState(0, 7, 8, true,
            770, 771, 1, 771, false, false, false, 15,
            1.0F, 1.0F, 1.0F, 1.0F);
        EarlyGlStateTracker.clientActiveTexture(33984);
        assertFalse(EarlyGlStateTracker.snapshot().hasHudState());

        EarlyGlStateTracker.seedHudState(true, false, 2, 3, 1280, 720);
        EarlyGlStateTracker.Snapshot seeded = EarlyGlStateTracker.snapshot();
        assertTrue(seeded.hasHudState());
        assertTrue(seeded.isTexture0Enabled());
        assertFalse(seeded.isTexture1Enabled());
        assertEquals(2, seeded.getViewportX());
        assertEquals(3, seeded.getViewportY());
        assertEquals(1280, seeded.getViewportWidth());
        assertEquals(720, seeded.getViewportHeight());

        EarlyGlStateTracker.activeTexture(33984);
        EarlyGlStateTracker.texture2dEnabled(false);
        EarlyGlStateTracker.viewport(0, 0, 640, 360);
        EarlyGlStateTracker.Snapshot changed = EarlyGlStateTracker.snapshot();
        assertFalse(changed.isTexture0Enabled());
        assertEquals(640, changed.getViewportWidth());
        assertEquals(360, changed.getViewportHeight());
    }

    @Test
    public void particleStateRequiresTrackedLightingAndPublishesItsValue() {
        EarlyGlStateTracker.beginProbe();
        publishComplete(0, 4, 0);
        EarlyGlStateTracker.bindBuffer(34962, 0);
        EarlyGlStateTracker.seedDrawState(0, 7, 8, true,
            770, 771, 1, 771, true, false, false, 15,
            1.0F, 1.0F, 1.0F, 1.0F);
        EarlyGlStateTracker.clientActiveTexture(33984);
        EarlyGlStateTracker.seedHudState(true, true, 0, 0, 800, 600);
        assertFalse(EarlyGlStateTracker.snapshot().hasParticleState());
        EarlyGlStateTracker.lightingEnabled(false);
        assertTrue(EarlyGlStateTracker.snapshot().hasParticleState());
        assertFalse(EarlyGlStateTracker.snapshot().isLighting());
        EarlyGlStateTracker.lightingEnabled(true);
        assertTrue(EarlyGlStateTracker.snapshot().isLighting());
    }

    @Test
    public void compatibilitySnapshotTracksFullSeedWithoutDriverQueries() {
        EarlyGlStateTracker.beginProbe();
        publishComplete(4, 5, 6);
        EarlyGlStateTracker.bindBuffer(34962, 7);
        EarlyGlStateTracker.seedDrawState(0, 8, 9, true,
            770, 771, 1, 771, true, true, true, 15,
            1.0F, 0.5F, 0.25F, 1.0F);
        EarlyGlStateTracker.clientActiveTexture(33984);
        EarlyGlStateTracker.seedHudState(true, false, 0, 0, 800, 600);
        int[] textures = new int[32];
        boolean[] enables = new boolean[32];
        textures[0] = 8;
        textures[1] = 9;
        enables[0] = true;
        EarlyGlStateTracker.seedCompatibilityState(3, true, 10, 11, 4,
            textures, enables, 32774, 32774, 1029, true, false,
            1, 2, 300, 200);
        EarlyGlStateTracker.seedDrawIndirectBuffer(15);
        EarlyGlStateTracker.bindBuffer(34963, 12);
        EarlyGlStateTracker.bindBuffer(35052, 13);
        assertEquals(13, EarlyGlStateTracker.pixelUnpackBufferBinding());
        EarlyGlStateTracker.activeTexture(33986);
        EarlyGlStateTracker.bindTexture(14);
        EarlyGlStateTracker.texture2dEnabled(true);
        EarlyGlStateTracker.blendEquation(32779);
        EarlyGlStateTracker.cullFace(1028);

        EarlyGlStateTracker.CompatibilitySnapshot snapshot =
            EarlyGlStateTracker.compatibilitySnapshot();
        assertNotNull(snapshot);
        assertEquals(3, snapshot.getVertexArray());
        assertEquals(12, snapshot.getElementBuffer());
        assertEquals(13, snapshot.getPixelUnpackBuffer());
        assertEquals(15, snapshot.getDrawIndirectBuffer());
        assertEquals(14, snapshot.getTexture2d(2));
        assertTrue(snapshot.isTexture2dEnabled(2));
        assertEquals(32779, snapshot.getBlendEquationRgb());
        assertEquals(1028, snapshot.getCullFace());
        EarlyGlStateTracker.invalidate();
        assertNull(EarlyGlStateTracker.compatibilitySnapshot());
        assertEquals(Integer.MIN_VALUE, EarlyGlStateTracker.arrayBufferBinding());
        assertEquals(Integer.MIN_VALUE,
            EarlyGlStateTracker.pixelUnpackBufferBinding());
    }

    @Test
    public void standardDepthRangeIsIndependentAndFailsClosed() {
        EarlyGlStateTracker.beginProbe();
        publishComplete(0, 0, 0);
        assertNotNull(EarlyGlStateTracker.snapshot());
        assertFalse(EarlyGlStateTracker.hasStandardDepthRange());
        assertFalse(EarlyGlStateTracker.hasKnownDepthRange());
        EarlyGlStateTracker.seedDepthRange(0.0D, 1.0D);
        assertTrue(EarlyGlStateTracker.hasKnownDepthRange());
        assertTrue(EarlyGlStateTracker.hasStandardDepthRange());
        EarlyGlStateTracker.seedDepthRange(0.1D, 1.0D);
        assertTrue(EarlyGlStateTracker.hasKnownDepthRange());
        assertFalse(EarlyGlStateTracker.hasStandardDepthRange());
        EarlyGlStateTracker.seedDepthRange(Double.NaN, 1.0D);
        assertFalse(EarlyGlStateTracker.hasKnownDepthRange());
        assertFalse(EarlyGlStateTracker.hasStandardDepthRange());
        assertNotNull(EarlyGlStateTracker.snapshot());
        EarlyGlStateTracker.invalidate();
        assertFalse(EarlyGlStateTracker.hasKnownDepthRange());
        assertFalse(EarlyGlStateTracker.hasStandardDepthRange());
    }

    private static void publishComplete(int program, int framebuffer, int packBuffer) {
        EarlyGlStateTracker.useProgram(program);
        EarlyGlStateTracker.bindFramebuffer(36160, framebuffer);
        EarlyGlStateTracker.depthFunction(513);
        EarlyGlStateTracker.bindBuffer(35051, packBuffer);
    }
}
