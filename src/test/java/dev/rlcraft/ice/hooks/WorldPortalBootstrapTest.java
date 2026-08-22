package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class WorldPortalBootstrapTest {
    @After public void reset() {
        WorldPortalBootstrap.resetForTest();
        FakeBridge.ended = 0L;
        FakeBridge.aborted = 0L;
        FakeBridge.error = null;
    }

    @Test
    public void missingDelegateIsAZeroCostFailOpenBoundary() {
        assertEquals(0L, WorldPortalBootstrap.begin());
        WorldPortalBootstrap.end(0L);
        WorldPortalBootstrap.abort(0L, new AssertionError());
    }

    @Test
    public void forwardsNormalAndExceptionalWrapperExitsExactlyOnce() {
        assertTrue(WorldPortalBootstrap.install(FakeBridge.class));
        long first = WorldPortalBootstrap.begin();
        assertEquals(41L, first);
        WorldPortalBootstrap.end(first);
        assertEquals(first, FakeBridge.ended);

        long second = WorldPortalBootstrap.begin();
        IllegalArgumentException original = new IllegalArgumentException("original");
        WorldPortalBootstrap.abort(second, original);
        assertEquals(second, FakeBridge.aborted);
        assertSame(original, FakeBridge.error);
    }

    public static final class FakeBridge {
        private static long ended;
        private static long aborted;
        private static Throwable error;

        public static long begin() { return 41L; }
        public static void end(long token) { ended = token; }
        public static void abort(long token, Throwable value) {
            aborted = token;
            error = value;
        }
    }
}
