package dev.rlcraft.ice.optimizer.compat.chunk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.junit.Test;

public final class ChunkAnimatorRenderBridgeTest {
    @Test
    public void readsPendingAnimationIdentityWithoutLinkingTheOptionalMod() {
        ChunkAnimatorRenderBridge.Probe probe =
            ChunkAnimatorRenderBridge.inspectForTest(FakeAnimator.class);
        Object chunk = new Object();
        assertEquals(ChunkAnimatorRenderBridge.Status.READY, probe.status());
        assertFalse(probe.requiresCompatibilityDraw(chunk));
        FakeAnimator.INSTANCE.animationHandler.timeStamps.put(chunk,
            new Object());
        assertTrue(probe.requiresCompatibilityDraw(chunk));
        FakeAnimator.INSTANCE.animationHandler.timeStamps.clear();
    }

    @Test
    public void incompatibleOptionalAbiFailsOpenForEveryRealChunk() {
        ChunkAnimatorRenderBridge.Probe probe =
            ChunkAnimatorRenderBridge.inspectForTest(BrokenAnimator.class);
        assertEquals(ChunkAnimatorRenderBridge.Status.FAILED, probe.status());
        assertNotNull(probe.failure());
        assertTrue(probe.requiresCompatibilityDraw(new Object()));
        assertFalse(probe.requiresCompatibilityDraw(null));
    }

    @Test
    public void runtimeMapFailurePermanentlyChangesTheProbeToFailOpen() {
        ChunkAnimatorRenderBridge.Probe probe =
            ChunkAnimatorRenderBridge.inspectForTest(ThrowingAnimator.class);
        assertEquals(ChunkAnimatorRenderBridge.Status.READY, probe.status());
        assertTrue(probe.requiresCompatibilityDraw(new Object()));
        assertEquals(ChunkAnimatorRenderBridge.Status.FAILED, probe.status());
        assertNotNull(probe.failure());
        assertTrue(probe.requiresCompatibilityDraw(new Object()));
    }

    public static final class FakeAnimator {
        static final FakeAnimator INSTANCE = new FakeAnimator();
        final FakeHandler animationHandler = new FakeHandler();
    }

    public static final class BrokenAnimator {
        static final BrokenAnimator INSTANCE = new BrokenAnimator();
    }

    public static final class ThrowingAnimator {
        static final ThrowingAnimator INSTANCE = new ThrowingAnimator();
        final ThrowingHandler animationHandler = new ThrowingHandler();
    }

    public static final class FakeHandler {
        final Map<Object, Object> timeStamps =
            new WeakHashMap<Object, Object>();
    }

    public static final class ThrowingHandler {
        final Map<Object, Object> timeStamps = new AbstractMap<Object, Object>() {
            @Override public boolean containsKey(Object key) {
                throw new IllegalStateException("injected map failure");
            }
            @Override public Set<Entry<Object, Object>> entrySet() {
                return Collections.emptySet();
            }
        };
    }
}
