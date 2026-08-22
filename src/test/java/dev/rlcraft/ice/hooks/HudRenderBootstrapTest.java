package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

public final class HudRenderBootstrapTest {
    @After
    public void reset() {
        HudRenderBootstrap.resetForTest();
        TestBridge.failure = null;
        TestBridge.committedFailure = null;
        TestBridge.posts = 0;
        TestBridge.committedCalls = 0;
    }

    @Test
    public void installsExactAbiAndDelegatesCandidates() {
        assertTrue(HudRenderBootstrap.install(TestBridge.class));
        assertEquals(41L, HudRenderBootstrap.begin(new Object(), 0.5F));
        assertTrue(HudRenderBootstrap.tryTexturedRect(new Object(),
            1, 2, 3, 4, 5, 6, 7.0F));
        assertTrue(HudRenderBootstrap.tryScaledTexture(1, 2, 3, 4,
            5, 6, 7, 8, 9, 10));
        assertEquals(42L, HudRenderBootstrap.fontStringBegin(new Object(),
            "cached", 1.0F, 2.0F, -1, false));
        assertEquals(77, HudRenderBootstrap.tryCachedFontString(42L,
            new Object(), "cached", 1.0F, 2.0F, -1, false));
        assertEquals(1, TestBridge.rectangles);
        assertEquals(1, TestBridge.scaled);
    }

    @Test
    public void eventExceptionIsPropagatedWithoutASecondPost() {
        assertTrue(HudRenderBootstrap.install(TestBridge.class));
        RuntimeException expected = new RuntimeException("handler");
        TestBridge.failure = expected;
        try {
            HudRenderBootstrap.post(new Object(), new Object());
            fail("expected event failure");
        } catch (RuntimeException actual) {
            assertSame(expected, actual);
        }
        assertEquals(1, TestBridge.posts);
    }

    @Test
    public void uncertainDelegateCommitIsPropagatedWithoutLegacyGlReplay() {
        assertTrue(HudRenderBootstrap.install(TestBridge.class));
        final RuntimeException expected = new RuntimeException(
            "delegate may already have submitted GL work");
        TestBridge.committedFailure = expected;

        assertCommittedFailure(expected, new Action() {
            @Override public void run() { HudRenderBootstrap.fontBegin(5); }
        });
        assertCommittedFailure(expected, new Action() {
            @Override public void run() {
                HudRenderBootstrap.fontTexCoord(0.25F, 0.75F);
            }
        });
        assertCommittedFailure(expected, new Action() {
            @Override public void run() {
                HudRenderBootstrap.fontVertex(1.0F, 2.0F, 3.0F);
            }
        });
        assertCommittedFailure(expected, new Action() {
            @Override public void run() { HudRenderBootstrap.fontEnd(); }
        });
        assertCommittedFailure(expected, new Action() {
            @Override public void run() {
                HudRenderBootstrap.directColor(1.0F, 0.5F, 0.25F, 1.0F);
            }
        });
        assertEquals(5, TestBridge.committedCalls);
    }

    @Test
    public void incompatibleBridgeLeavesCandidatesOnLegacyPath() {
        assertFalse(HudRenderBootstrap.install(Incompatible.class));
        assertEquals(0L, HudRenderBootstrap.begin(new Object(), 0.0F));
        assertFalse(HudRenderBootstrap.tryCustomTexture(0, 0, 0, 0,
            1, 1, 256, 256));
    }

    public static final class TestBridge {
        static Throwable failure;
        static RuntimeException committedFailure;
        static int posts;
        static int rectangles;
        static int scaled;
        static int committedCalls;

        public static long begin(Object overlay, float partialTicks) { return 41L; }
        public static void barrier() { }
        public static boolean post(Object bus, Object event) throws Throwable {
            posts++;
            if (failure != null) throw failure;
            return true;
        }
        public static boolean tryTexturedRect(Object gui, int x, int y, int u,
                                              int v, int width, int height,
                                              float z) {
            rectangles++;
            return true;
        }
        public static boolean tryTexturedRectFloat(Object gui, float x, float y,
                                                   int u, int v, int width,
                                                   int height, float z) {
            return true;
        }
        public static boolean tryTexturedSprite(Object gui, int x, int y,
                                                Object sprite, int width,
                                                int height, float z) {
            return true;
        }
        public static boolean tryCustomTexture(int x, int y, float u, float v,
                                               int width, int height,
                                               float textureWidth,
                                               float textureHeight) {
            return true;
        }
        public static boolean tryScaledTexture(int x, int y, float u, float v,
                                               int sourceWidth, int sourceHeight,
                                               int width, int height,
                                               float textureWidth,
                                               float textureHeight) {
            scaled++;
            return true;
        }
        public static void end(long token) { }
        public static void abort(long token, Throwable error) { }
        public static long fontStringBegin(Object font, String text, float x,
                                           float y, int color,
                                           boolean shadow) { return 42L; }
        public static int tryCachedFontString(long token, Object font,
                                              String text, float x, float y,
                                              int color, boolean shadow) {
            return 77;
        }
        public static void fontStringEnd(long token) { }
        public static void fontStringAbort(long token, Throwable error) { }
        public static void fontBegin(int mode) { failAfterPossibleCommit(); }
        public static void fontTexCoord(float u, float v) {
            failAfterPossibleCommit();
        }
        public static void fontVertex(float x, float y, float z) {
            failAfterPossibleCommit();
        }
        public static void fontEnd() { failAfterPossibleCommit(); }
        public static void directColor(float red, float green, float blue,
                                       float alpha) { failAfterPossibleCommit(); }
        public static void textureBarrier(int texture) { }

        private static void failAfterPossibleCommit() {
            committedCalls++;
            if (committedFailure != null) throw committedFailure;
        }
    }

    public static final class Incompatible {
        public static long begin(Object overlay) { return 0L; }
    }

    private static void assertCommittedFailure(RuntimeException expected,
                                               Action action) {
        try {
            action.run();
            fail("delegate failure was swallowed or replayed");
        } catch (RuntimeException actual) {
            assertSame(expected, actual);
        }
    }

    private interface Action {
        void run();
    }
}
