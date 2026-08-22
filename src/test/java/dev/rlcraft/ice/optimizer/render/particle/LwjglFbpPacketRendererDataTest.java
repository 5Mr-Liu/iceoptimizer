package dev.rlcraft.ice.optimizer.render.particle;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public final class LwjglFbpPacketRendererDataTest {
    @Test
    public void restoreFailureInvalidatesTheSoftwareMirror() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(36160, 0);
        EarlyGlStateTracker.depthFunction(513);
        EarlyGlStateTracker.bindBuffer(35051, 0);
        Throwable restore = new IllegalStateException("restore");
        assertSame(restore,
            LwjglFbpPacketRenderer.restoreFailure(null, restore));
        assertFalse(EarlyGlStateTracker.isKnown());
        assertEquals(0, restore.getSuppressed().length);
    }

    @Test
    public void acceptsOnlyTheExactVanillaBlockPacketLayout() {
        LwjglFbpPacketRenderer.Validation validation =
            LwjglFbpPacketRenderer.validateFormat();
        assertTrue(validation.getDetail(), validation.isEquivalent());
        assertTrue(LwjglFbpPacketRenderer.exactStrideBytes() == 28);
        assertTrue(LwjglFbpPacketRenderer.isExactBlockFormat(
            DefaultVertexFormats.BLOCK));
        assertFalse(LwjglFbpPacketRenderer.isExactBlockFormat(
            DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP));
    }

    @Test
    public void fenceFailureStopsBeforeAnotherPacketSlotCanDraw()
        throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard,
            new CacheBudget(1L, 1L, 1024L * 1024L),
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) { }
            }, 16);
        LwjglFbpPacketRenderer renderer = new LwjglFbpPacketRenderer(guard,
            ledger, 4096);
        try {
            Field slotsField = LwjglFbpPacketRenderer.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            Field bufferField = slots[0].getClass().getDeclaredField("bufferId");
            bufferField.setAccessible(true);
            bufferField.setInt(slots[0], 9);
            Field fenceField = slots[0].getClass().getDeclaredField("fence");
            fenceField.setAccessible(true);
            final IllegalStateException injected =
                new IllegalStateException("injected FBP Fence failure");
            fenceField.set(slots[0], new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { throw injected; }
                @Override public void destroy() { }
            });

            Method acquire = LwjglFbpPacketRenderer.class.getDeclaredMethod(
                "acquireSlot");
            acquire.setAccessible(true);
            assertEquals(null, acquire.invoke(renderer));
            assertSame(injected, renderer.getLastError());
        } finally {
            renderer.close(false);
        }
    }

    @Test
    public void submittedFatalPoisonsPacketSlotBeforeEscaping()
        throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard,
            new CacheBudget(1L, 1L, 1024L * 1024L),
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,
                                               int nativeId) { }
            }, 16);
        LwjglFbpPacketRenderer renderer = new LwjglFbpPacketRenderer(guard,
            ledger, 4096);
        try {
            Field slotsField = LwjglFbpPacketRenderer.class.getDeclaredField(
                "slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            Field errorField = LwjglFbpPacketRenderer.class.getDeclaredField(
                "lastError");
            errorField.setAccessible(true);
            OutOfMemoryError fatal = new OutOfMemoryError("FBP fatal");
            errorField.set(renderer, new IllegalStateException(
                "wrapped FBP fatal", fatal));
            Method finish = LwjglFbpPacketRenderer.class.getDeclaredMethod(
                "finishFailedSubmission", slots[0].getClass());
            finish.setAccessible(true);
            try {
                finish.invoke(renderer, slots[0]);
                throw new AssertionError("wrapped fatal was swallowed");
            } catch (InvocationTargetException expected) {
                assertSame(fatal, expected.getCause());
            }
            assertEquals(1L, renderer.getModernPackets());
            Field poisoned = slots[0].getClass().getDeclaredField("poisoned");
            poisoned.setAccessible(true);
            assertTrue(poisoned.getBoolean(slots[0]));
            Field fence = slots[0].getClass().getDeclaredField("fence");
            fence.setAccessible(true);
            assertFalse(((ResourceLedger.RetirementFence) fence.get(slots[0]))
                .isSignaled());
        } finally {
            renderer.close(false);
        }
    }
}
