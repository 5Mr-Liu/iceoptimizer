package dev.rlcraft.ice.optimizer.render.hud;

import static org.junit.Assert.assertEquals;
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
import org.junit.Test;
import org.lwjgl.opengl.GL11;

public final class LwjglHudRendererDataTest {
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
    public void mergesQuadsAndRejectsMixedTopologyUntilCallerFlushes() {
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
            assertFalse(renderer.recordGlyph(glyph));
            assertEquals(1, renderer.getCommandCount());
            assertEquals(8, renderer.getVertexCount());
            renderer.discard();
            assertTrue(renderer.recordGlyph(glyph));
            assertEquals(1, renderer.getCommandCount());
            assertEquals(4, renderer.getVertexCount());
        } finally {
            fixture.close();
        }
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
    public void fenceFailureIsReportedBeforeAQueuedBatchCanDraw() throws Exception {
        Fixture fixture = new Fixture();
        try {
            LwjglHudRenderer renderer = fixture.renderer;
            Field slotsField = LwjglHudRenderer.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(renderer);
            Field fenceField = slots[0].getClass().getDeclaredField("fence");
            fenceField.setAccessible(true);
            final IllegalStateException injected =
                new IllegalStateException("injected HUD Fence failure");
            fenceField.set(slots[0], new ResourceLedger.RetirementFence() {
                @Override public boolean isSignaled() { throw injected; }
                @Override public void destroy() { }
            });

            Method acquire = LwjglHudRenderer.class.getDeclaredMethod("acquireSlot");
            acquire.setAccessible(true);
            try {
                acquire.invoke(renderer);
                throw new AssertionError("expected Fence failure");
            } catch (InvocationTargetException expected) {
                assertSame(injected, expected.getCause());
            }
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
}
