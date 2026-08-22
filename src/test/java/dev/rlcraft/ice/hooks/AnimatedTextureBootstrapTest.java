package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.bridge.UnsafeLegacyReplayException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.After;
import org.junit.Test;

public final class AnimatedTextureBootstrapTest {
    @After public void reset() { AnimatedTextureBootstrap.resetForTest(); }

    @Test
    public void absentDelegateLeavesEveryOriginalCallAvailable() {
        assertEquals(0L, AnimatedTextureBootstrap.begin(new Object()));
        AnimatedTextureBootstrap.beforeSprite(new Object());
        AnimatedTextureBootstrap.afterSprite();
        AnimatedTextureBootstrap.textureBarrier();
        assertFalse(AnimatedTextureBootstrap.tryUpload(new int[][] {{1}},
            1, 1, 0, 0, false, false));
        AnimatedTextureBootstrap.end(1L);
        AnimatedTextureBootstrap.abort(1L, new IllegalStateException("test"));
    }

    @Test
    public void delegatesTheWholeScopeAfterAtomicInstallation() {
        WorkingBridge.reset();
        assertTrue(AnimatedTextureBootstrap.install(WorkingBridge.class));
        assertEquals(41L, AnimatedTextureBootstrap.begin("atlas"));
        AnimatedTextureBootstrap.beforeSprite("sprite");
        AnimatedTextureBootstrap.textureBarrier();
        assertTrue(AnimatedTextureBootstrap.tryUpload(new int[][] {{7}},
            1, 1, 2, 3, false, true));
        AnimatedTextureBootstrap.afterSprite();
        AnimatedTextureBootstrap.end(41L);
        AnimatedTextureBootstrap.abort(42L, new IllegalArgumentException("x"));
        assertEquals(1, WorkingBridge.before);
        assertEquals(1, WorkingBridge.after);
        assertEquals(1, WorkingBridge.barriers);
        assertEquals(41L, WorkingBridge.ended);
        assertEquals(42L, WorkingBridge.aborted);
    }

    @Test
    public void delegateFailureDoesNotSwallowTheUpload() {
        assertTrue(AnimatedTextureBootstrap.install(ThrowingBridge.class));
        assertEquals(0L, AnimatedTextureBootstrap.begin(new Object()));
        assertFalse(AnimatedTextureBootstrap.tryUpload(new int[][] {{1}},
            1, 1, 0, 0, false, false));
    }

    @Test
    public void uncertainReplayFailureEscapesEveryCommittingBoundary() {
        CommittedFailureBridge.reset();
        assertTrue(AnimatedTextureBootstrap.install(
            CommittedFailureBridge.class));
        assertUnsafe(new Runnable() {
            @Override public void run() {
                AnimatedTextureBootstrap.begin(new Object());
            }
        });
        assertUnsafe(new Runnable() {
            @Override public void run() {
                AnimatedTextureBootstrap.beforeSprite(new Object());
            }
        });
        assertUnsafe(new Runnable() {
            @Override public void run() {
                AnimatedTextureBootstrap.textureBarrier();
            }
        });
        assertUnsafe(new Runnable() {
            @Override public void run() {
                AnimatedTextureBootstrap.tryUpload(new int[][] {{1}},
                    1, 1, 0, 0, false, false);
            }
        });
        assertUnsafe(new Runnable() {
            @Override public void run() {
                AnimatedTextureBootstrap.end(1L);
            }
        });
    }

    @Test
    public void coreBootstrapLoadsWithoutMinecraftOrMainClasses() throws Exception {
        URL coreOutput = AnimatedTextureBootstrap.class.getProtectionDomain()
            .getCodeSource().getLocation();
        URLClassLoader isolated = new URLClassLoader(new URL[] {coreOutput}, null);
        try {
            Class<?> type = Class.forName(
                "dev.rlcraft.ice.hooks.AnimatedTextureBootstrap", true, isolated);
            Method upload = type.getMethod("tryUpload", int[][].class,
                int.class, int.class, int.class, int.class,
                boolean.class, boolean.class);
            assertFalse((Boolean) upload.invoke(null, new int[][] {{1}},
                1, 1, 0, 0, false, false));
        } finally {
            isolated.close();
        }
    }

    public static final class WorkingBridge {
        static int before;
        static int after;
        static int barriers;
        static long ended;
        static long aborted;
        static void reset() {
            before = after = barriers = 0;
            ended = aborted = 0L;
        }
        public static long begin(Object atlas) { return "atlas".equals(atlas) ? 41L : 0L; }
        public static void beforeSprite(Object sprite) { if ("sprite".equals(sprite)) before++; }
        public static void afterSprite() { after++; }
        public static void textureBarrier() { barriers++; }
        public static boolean tryUpload(int[][] data, int width, int height,
                                        int x, int y, boolean blur, boolean clamp) {
            return data[0][0] == 7 && width == 1 && height == 1
                && x == 2 && y == 3 && !blur && clamp;
        }
        public static void end(long token) { ended = token; }
        public static void abort(long token, Throwable error) { aborted = token; }
    }

    public static final class ThrowingBridge {
        public static long begin(Object atlas) { throw new IllegalStateException(); }
        public static void beforeSprite(Object sprite) { throw new IllegalStateException(); }
        public static void afterSprite() { throw new IllegalStateException(); }
        public static void textureBarrier() { throw new IllegalStateException(); }
        public static boolean tryUpload(int[][] data, int width, int height,
                                        int x, int y, boolean blur, boolean clamp) {
            throw new IllegalStateException();
        }
        public static void end(long token) { throw new IllegalStateException(); }
        public static void abort(long token, Throwable error) { throw new IllegalStateException(); }
    }

    public static final class CommittedFailureBridge {
        private static UnsafeLegacyReplayException failure;
        static void reset() {
            failure = new UnsafeLegacyReplayException("committed test",
                new IllegalStateException("legacy replay"));
        }
        private static UnsafeLegacyReplayException failure() { return failure; }
        public static long begin(Object atlas) { throw failure(); }
        public static void beforeSprite(Object sprite) { throw failure(); }
        public static void afterSprite() { throw failure(); }
        public static void textureBarrier() { throw failure(); }
        public static boolean tryUpload(int[][] data, int width, int height,
                                        int x, int y, boolean blur,
                                        boolean clamp) {
            throw failure();
        }
        public static void end(long token) { throw failure(); }
        public static void abort(long token, Throwable error) { throw failure(); }
    }

    private static void assertUnsafe(Runnable call) {
        try {
            call.run();
            fail("uncertain replay failure was swallowed");
        } catch (UnsafeLegacyReplayException expected) {
            assertSame(CommittedFailureBridge.failure, expected);
        }
    }
}
