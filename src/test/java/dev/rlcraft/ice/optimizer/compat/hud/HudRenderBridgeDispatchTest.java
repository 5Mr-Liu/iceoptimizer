package dev.rlcraft.ice.optimizer.compat.hud;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.ListenerList;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.junit.Test;

public final class HudRenderBridgeDispatchTest {
    public static class HudEvent extends Event {
        private static final ListenerList LISTENERS = new ListenerList();
        @Override public boolean isCancelable() { return true; }
        @Override public ListenerList getListenerList() { return LISTENERS; }
    }
    public static final class Listener {
        int calls;
        RuntimeException failure;
        public void handle(HudEvent event) {
            calls++;
            if (failure != null) throw failure;
            event.setCanceled(true);
        }
    }

    @Test public void actualForgePostIsOnceAndInvalidatesWithoutEagerNativeQueries() throws Exception {
        EventBus bus = new EventBus();
        Listener listener = new Listener();
        register(bus, listener);
        Object state = state();
        Object scope = enterScope(state);
        try {
            publishTrackedState();
            assertTrue(HudRenderBridge.post(bus, new HudEvent()));
            assertEquals(1, listener.calls);
            assertFalse(EarlyGlStateTracker.isKnown());
            assertTrue(field(scope, "stateNeedsSync").getBoolean(scope));
            assertEquals(0, field(state, "legacyDepth").getInt(state));
            // This test has no GL context. A second event still needs no query.
            assertTrue(HudRenderBridge.post(bus, new HudEvent()));
            assertEquals(2, listener.calls);
        } finally { field(state, "hudDepth").setInt(state, 0); }
    }

    @Test public void originalForgeExceptionPropagatesWithoutDuplicatePost() throws Exception {
        EventBus bus = new EventBus((ignored, event, listeners, index, failure) -> { });
        Listener listener = new Listener();
        listener.failure = new IllegalStateException("event failed");
        register(bus, listener);
        Object state = state();
        Object scope = enterScope(state);
        try {
            try {
                HudRenderBridge.post(bus, new HudEvent());
                fail("expected original listener exception");
            } catch (RuntimeException actual) { assertSame(listener.failure, actual); }
            assertEquals(1, listener.calls);
            assertEquals(0, field(state, "legacyDepth").getInt(state));
            assertTrue(field(scope, "stateNeedsSync").getBoolean(scope));
        } finally { field(state, "hudDepth").setInt(state, 0); }
    }

    @Test public void emptyActualPostIsDirectAndPreservesKnownTracker() {
        publishTrackedState();
        assertTrue(EarlyGlStateTracker.isKnown());
        assertFalse(HudRenderBridge.post(new EventBus(), new Event()));
        assertTrue(EarlyGlStateTracker.isKnown());
    }

    @Test public void queuedBatchGenerationMustMatchAtSubmissionTime() {
        assertTrue(HudRenderBridge.hudGenerationCurrent(7, 11, 7, 11));
        assertFalse(HudRenderBridge.hudGenerationCurrent(7, 11, 8, 11));
        assertFalse(HudRenderBridge.hudGenerationCurrent(7, 11, 7, 12));
        assertFalse(HudRenderBridge.hudGenerationCurrent(0, 11, 0, 11));
    }

    private static void register(EventBus bus, Listener listener) throws Exception {
        int busId = field(bus, "busID").getInt(bus);
        new HudEvent().getListenerList().register(busId, EventPriority.NORMAL,
            event -> listener.handle((HudEvent) event));
    }
    private static Object state() throws Exception {
        Field field = HudRenderBridge.class.getDeclaredField("STATE");
        field.setAccessible(true);
        return ((ThreadLocal<?>) field.get(null)).get();
    }
    private static Object enterScope(Object state) throws Exception {
        Object scope = ((Object[]) field(state, "hudScopes").get(state))[0];
        Method reset = scope.getClass().getDeclaredMethod("reset", long.class);
        reset.setAccessible(true); reset.invoke(scope, 1L);
        field(state, "hudDepth").setInt(state, 1);
        return scope;
    }
    private static Field field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true); return field;
    }
    private static void publishTrackedState() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.useProgram(7);
        EarlyGlStateTracker.bindFramebuffer(36160, 8);
        EarlyGlStateTracker.depthFunction(515);
        EarlyGlStateTracker.bindBuffer(35051, 9);
    }
}
