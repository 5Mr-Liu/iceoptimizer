package dev.rlcraft.ice.optimizer.render.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.opengl.GL11;

public final class LwjglHudRendererDataTest {
    @Test public void hudReadinessDoesNotInventUnqueriedWorldState() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindBuffer(34962, 7);
        EarlyGlStateTracker.bindVertexArray(9);
        EarlyGlStateTracker.activeTexture(33984);
        EarlyGlStateTracker.clientActiveTexture(33984);
        EarlyGlStateTracker.bindTexture(5);
        EarlyGlStateTracker.texture2dEnabled(true);
        EarlyGlStateTracker.viewport(0, 0, 128, 64);
        assertTrue(EarlyGlStateTracker.isHudStreamStateSafe());
        assertTrue(LwjglHudRenderer.isSafeState(EarlyGlStateTracker.hudSnapshot()));
        assertFalse(EarlyGlStateTracker.isKnown());
        assertEquals(null, EarlyGlStateTracker.snapshot());
        assertFalse(EarlyGlStateTracker.hudSnapshot().hasDrawState());
        EarlyGlStateTracker.activeTexture(33985);
        assertFalse(EarlyGlStateTracker.isHudStreamStateSafe());
        EarlyGlStateTracker.invalidate();
        assertEquals(null, EarlyGlStateTracker.hudSnapshot());
    }
    @Test
    public void immutableLayoutProducesExactlyTheSameVerticesAsCheckedGlyphs() throws Exception {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1L, 1L);
            float[] glyphs = new float[40];
            for (int i = 0; i < glyphs.length; i++) glyphs[i] = i * 0.25F;
            FontLayoutCache.GlyphLayout layout = new FontLayoutCache.GlyphLayout(
                glyphs, new int[] {1, 1});
            assertTrue(renderer.recordGlyphRun(glyphs, 2, 10.0F, 20.0F));
            byte[] expected = vertexBytes(renderer);
            renderer.discard();
            glyphs[0] = Float.NaN;
            layout.getGlyphGeometry()[0] = Float.NaN;
            assertTrue(layout.record(renderer, 10.0F, 20.0F));
            assertEquals(2, renderer.getCommandCount());
            assertArrayEquals(expected, vertexBytes(renderer));
            assertFalse(layout.record(renderer, Float.NaN, 0.0F));
            assertEquals(2, renderer.getCommandCount());
            assertArrayEquals(expected, vertexBytes(renderer));
        } finally {
            fixture.close();
        }
    }

    private static byte[] vertexBytes(LwjglHudRenderer renderer) throws Exception {
        Method materialize = LwjglHudRenderer.class.getDeclaredMethod("materializeDeferredGlyphs");
        materialize.setAccessible(true);
        materialize.invoke(renderer);
        Field field = LwjglHudRenderer.class.getDeclaredField("vertices");
        field.setAccessible(true);
        ByteBuffer buffer = ((ByteBuffer) field.get(renderer)).duplicate();
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    @Test public void pagedFontRangesMatchIndependentGlyphsAfterDeferredAppend() throws Exception {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1, 1);
            float[] data = new float[280];
            for (int index = 0; index < data.length; index++) data[index] = index * 0.125F;
            int[] pages = {1, 1, 7, 7, 3, 3, 3, 3, 3, 3, 3, 3, 3, 1};
            FontLayoutCache.GlyphLayout layout = new FontLayoutCache.GlyphLayout(data, pages);
            for (int run = 0; run < layout.runCount(); run++) {
                int first = layout.runStart(run), count = layout.runGlyphCount(run);
                float[] isolated = java.util.Arrays.copyOfRange(data, first * 20, (first + count) * 20);
                assertTrue(renderer.recordGlyphRun(isolated, count, 4.25F, -3.5F));
                assertTrue(renderer.recordGlyphRun(isolated, count, 2F, 7F));
                byte[] expected = vertexBytes(renderer);
                renderer.discard();
                assertTrue(layout.recordRun(renderer, run, 4.25F, -3.5F));
                // Forces both the small deferred slice and the large direct
                // slice to coexist with a subsequent append.
                assertTrue(layout.recordRun(renderer, run, 2F, 7F));
                assertArrayEquals(expected, vertexBytes(renderer));
                renderer.discard();
            }
            assertFalse(layout.record(renderer, 0, 0));
            assertFalse(renderer.recordValidatedGlyphRange(data, Integer.MAX_VALUE, 1, 0, 0));
            assertFalse(renderer.recordValidatedGlyphRange(data, 1, Integer.MAX_VALUE, 0, 0));
            assertEquals(0, renderer.getCommandCount());
        } finally { fixture.close(); }
    }

    @Test
    public void restoreFailureInvalidatesTrackedGlState() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(36160, 0);
        EarlyGlStateTracker.depthFunction(513);
        EarlyGlStateTracker.bindBuffer(35051, 0);
        Throwable error = new IllegalStateException("restore");
        assertSame(error, LwjglHudRenderer.restoreFailure(null, error));
        assertFalse(EarlyGlStateTracker.isKnown());
    }

    @Test
    public void mergesAdjacentQuadsAndKeepsMixedTopologyInPainterOrder() throws Exception {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1L, 1L);
            assertTrue(renderer.recordQuad(0, 0, 0, 0, 0, 1, 1, 1, 1));
            assertTrue(renderer.recordQuad(1, 0, 0, 0, 0, 2, 1, 1, 1));
            assertEquals(1, renderer.getCommandCount());
            assertEquals(8, renderer.getVertexCount());

            float[] glyph = {
                0, 0, 0, 0, 0,
                1, 0, 0, 1, 0,
                0, 1, 0, 0, 1,
                1, 1, 0, 1, 1
            };
            assertTrue(renderer.recordGlyph(glyph));
            assertTrue(renderer.recordQuad(2, 0, 0, 0, 0, 3, 1, 1, 1));
            assertEquals(3, renderer.getCommandCount());
            assertEquals(16, renderer.getVertexCount());
            assertArrayEquals(new int[] {GL11.GL_QUADS, GL11.GL_TRIANGLE_STRIP, GL11.GL_QUADS},
                commandData(renderer, "modes"));
            assertArrayEquals(new int[] {0, 8, 12}, commandData(renderer, "firsts"));
            assertArrayEquals(new int[] {8, 4, 4}, commandData(renderer, "counts"));
        } finally {
            fixture.close();
        }
    }

    private static int[] commandData(LwjglHudRenderer renderer, String name) throws Exception {
        Field field = LwjglHudRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return java.util.Arrays.copyOf((int[]) field.get(renderer), renderer.getCommandCount());
    }

    @Test
    public void incompleteImmediateGlyphRollsBackWithoutConsumingCapacity() {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1L, 1L);
            assertTrue(renderer.beginImmediate(GL11.GL_TRIANGLE_STRIP));
            renderer.immediateTexCoord(0, 0);
            renderer.immediateVertex(1, 2, 3);
            assertFalse(renderer.endImmediate());
            assertEquals(0, renderer.getCommandCount());
            assertEquals(0, renderer.getVertexCount());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void cachedGlyphRunIsValidatedAndAppendedAtomically() {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1L, 1L);
            float[] glyphs = new float[40];
            glyphs[0] = 1.0F;
            glyphs[20] = 2.0F;
            assertTrue(renderer.recordGlyphRun(glyphs, 2, 10.0F, 20.0F));
            assertEquals(2, renderer.getCommandCount());
            assertEquals(8, renderer.getVertexCount());
            float[] invalid = glyphs.clone();
            invalid[23] = Float.NaN;
            assertFalse(renderer.recordGlyphRun(invalid, 2, 0.0F, 0.0F));
            assertEquals(2, renderer.getCommandCount());
            assertEquals(8, renderer.getVertexCount());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void uncertainSubmittedSlotIsSkippedWithoutPollingItsFence() throws Exception {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            Field slotsField = LwjglHudRenderer.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            Field fenceField = slots[0].getClass().getDeclaredField("fence");
            fenceField.setAccessible(true);
            Field poisoned = slots[0].getClass().getDeclaredField("poisoned");
            poisoned.setAccessible(true);
            poisoned.setBoolean(slots[0],true);
            fenceField.set(slots[0], new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { throw new AssertionError("uncertain fence polled"); }
                @Override public void destroy() { throw new AssertionError("uncertain fence destroyed"); }
            });
            Field buffer = slots[1].getClass().getDeclaredField("bufferId");
            buffer.setAccessible(true);
            buffer.setInt(slots[1],9);

            Method acquire = LwjglHudRenderer.class.getDeclaredMethod("acquireSlot");
            acquire.setAccessible(true);
            assertSame(slots[1],acquire.invoke(renderer));
            assertTrue(poisoned.getBoolean(slots[0]));
            assertEquals(null,renderer.getLastError());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void wrappedFatalBeforeDrawClearsQueuedHudGeometry() throws Exception {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1L, 1L);
            assertTrue(renderer.recordQuad(0, 0, 0, 0, 0, 1, 1, 1, 1));
            OutOfMemoryError fatal = new OutOfMemoryError("HUD fatal");
            Method fallback = LwjglHudRenderer.class.getDeclaredMethod(
                "legacyAndClear", LwjglHudRenderer.FlushResult.class,
                Throwable.class);
            fallback.setAccessible(true);
            try {
                fallback.invoke(renderer,
                    LwjglHudRenderer.FlushResult.FAILED_BEFORE_DRAW,
                    new IllegalStateException("wrapped HUD fatal", fatal));
                throw new AssertionError("wrapped fatal was swallowed");
            } catch (InvocationTargetException expected) {
                assertSame(fatal, expected.getCause());
            }
            assertEquals(0, renderer.getCommandCount());
            assertEquals(0, renderer.getVertexCount());
        } finally {
            fixture.close();
        }
    }

    @Test public void sealedMappedPageCannotAppendEvenWhenASmallerBatchFits() throws Exception {
        Fixture fixture = new Fixture();
        final boolean[] ready = {false};
        final int[] destroyed = {0};
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            renderer.prepare(1, 1);
            assertTrue(renderer.recordQuad(0, 0, 0, 0, 0, 1, 1, 1, 1));
            Field slotsField = LwjglHudRenderer.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            setSlot(slots[0], "bufferId", 11);
            setSlot(slots[0], "handle", fixture.ledger.registerReserved(
                RenderResourceKind.BUFFER, 11, 512, 1, 1, fixture.ledger.reserveGpu(512)));
            setSlot(slots[0], "mapped", java.nio.ByteBuffer.allocateDirect(512));
            setSlot(slots[0], "writeOffset", 400); // 80-byte batch fits, but page is sealed.
            setSlot(slots[0], "fence", new ResourceLedger.RetirementFence() {
                public boolean isSignaled() { return ready[0]; }
                public void destroy() { destroyed[0]++; }
            });
            setSlot(slots[1], "bufferId", 12);
            Method acquire = LwjglHudRenderer.class.getDeclaredMethod("acquireSlot");
            acquire.setAccessible(true);
            assertSame(slots[1], acquire.invoke(renderer));
            assertEquals(0, destroyed[0]);
            ready[0] = true;
            Field cursor = LwjglHudRenderer.class.getDeclaredField("nextSlot");
            cursor.setAccessible(true); cursor.setInt(renderer, 0);
            assertSame(slots[0], acquire.invoke(renderer));
            assertEquals(1, destroyed[0]);
            Field offset = slots[0].getClass().getDeclaredField("writeOffset");
            offset.setAccessible(true);
            assertEquals(0, offset.getInt(slots[0]));
        } finally {
            fixture.close();
            fixture.ledger.destroyAll(1);
            assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
        }
    }

    private static void setSlot(Object slot, String name, Object value) throws Exception {
        Field field = slot.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(slot, value);
    }

    private static final class Fixture {
        private final RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        private final CacheBudget budget = new CacheBudget(1024 * 1024,
            16 * 1024 * 1024, 16 * 1024 * 1024);
        private final ResourceLedger ledger = new ResourceLedger(guard, budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) { }
            }, 32);
        private final LwjglHudRenderer renderer = new LwjglHudRenderer(
            guard, ledger, budget, 64);

        private void close() { renderer.close(false); }
    }

    @Test public void ordinaryPayloadBudgetMissKeepsHudSlotsReusable() throws Exception {
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        CacheBudget budget = new CacheBudget(1,16<<20,0);
        ResourceLedger ledger = new ResourceLedger(guard,budget,
            new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind,int id) { }
            },16);
        LwjglHudRenderer renderer = new LwjglHudRenderer(guard,ledger,budget,65536);
        renderer.prepare(1,1);
        try {
            assertTrue(renderer.recordQuad(0,0,0,0,0,1,1,1,1));
            Method acquire = LwjglHudRenderer.class.getDeclaredMethod("acquireSlot"); acquire.setAccessible(true);
            for(int attempt=0;attempt<4;attempt++) assertEquals(null,acquire.invoke(renderer));
            assertEquals(null,renderer.getLastError());
            assertEquals(4,renderer.getVertexCount());
            Field slotsField = LwjglHudRenderer.class.getDeclaredField("slots"); slotsField.setAccessible(true);
            for(Object slot:(Object[])slotsField.get(renderer)) {
                Field poisoned = slot.getClass().getDeclaredField("poisoned"); poisoned.setAccessible(true);
                assertFalse(poisoned.getBoolean(slot));
            }
        } finally { renderer.close(false); }
    }
}
