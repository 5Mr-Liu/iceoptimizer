package dev.rlcraft.ice.optimizer.compat.events;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ServerEventWorkspaceTest {
    @Test public void interfaceNamesAreCanonicalButProvidersStayLive() {
        Map<String, Object> providers = new IdentityHashMap<String, Object>();
        Object first = new Object(), second = new Object();
        String name = Runnable.class.getName().intern();
        providers.put(name, first);
        assertTrue(LibrarianCapabilityBridge.matches(new Class<?>[] { Runnable.class }, providers, first));
        providers.put(name, second);
        assertFalse(LibrarianCapabilityBridge.matches(new Class<?>[] { Runnable.class }, providers, first));
        assertTrue(LibrarianCapabilityBridge.matches(new Class<?>[] { Runnable.class }, providers, second));
        assertFalse(LibrarianCapabilityBridge.matches(new Class<?>[0], providers, first));
    }
    @Test public void snapshotsRetainOrderLengthAndArrayTypeWithoutRetainingObjects() {
        Object[] first;
        ServerSnapshotBridge.begin();
        try {
            first = ServerSnapshotBridge.snapshot(Arrays.asList("a", "b"), 2, String.class, 0);
            assertEquals(String[].class, first.getClass());
            assertArrayEquals(new String[] { "a", "b" }, first);
        } finally { ServerSnapshotBridge.end(); }
        assertArrayEquals(new Object[] { null, null }, first);
        ServerSnapshotBridge.begin();
        try {
            assertSame(first, ServerSnapshotBridge.snapshot(Arrays.asList("c", "d"), 2, String.class, 0));
            assertEquals(0, ServerSnapshotBridge.snapshot(Collections.emptyList(), 0, String.class, 1).length);
            assertArrayEquals(new String[] { "z" }, ServerSnapshotBridge.snapshot(Collections.singletonList("z"), 1, String.class, 0));
        } finally { ServerSnapshotBridge.end(); }
    }
    @Test public void nestedCallsCannotOverwriteAnOuterSnapshot() {
        ServerSnapshotBridge.begin();
        try {
            Object[] outer = ServerSnapshotBridge.snapshot(Collections.singletonList("outer"), 1, String.class, 2);
            ServerSnapshotBridge.begin();
            try {
                Object[] inner = ServerSnapshotBridge.snapshot(Collections.singletonList("inner"), 1, String.class, 2);
                assertNotSame(outer, inner);
                assertEquals("outer", outer[0]);
            } finally { ServerSnapshotBridge.end(); }
            assertEquals("outer", outer[0]);
        } finally { ServerSnapshotBridge.end(); }
    }
    @Test public void processorsKeepFilterMapOrderAndNestedCallbackOwnership() {
        final List<String> calls = new ArrayList<String>();
        final ServerProcessorAccess plain = new ServerProcessorAccess() {
            public boolean ice$hasProcessor(Object tile) { calls.add("has:" + tile); return tile != null; }
            public Object ice$getProcessor(Object tile) { calls.add("get:" + tile); return tile; }
        };
        ServerSnapshotBridge.begin();
        List<Object> result;
        try {
            result = ServerSnapshotBridge.processors(Arrays.asList("a", null, "b"), new ServerProcessorAccess() {
                public boolean ice$hasProcessor(Object tile) { return plain.ice$hasProcessor(tile); }
                public Object ice$getProcessor(Object tile) {
                    if ("b".equals(tile)) {
                        ServerSnapshotBridge.begin();
                        try { assertEquals(Collections.singletonList("nested"), ServerSnapshotBridge.processors(Collections.singletonList("nested"), plain)); }
                        finally { ServerSnapshotBridge.end(); }
                    }
                    return plain.ice$getProcessor(tile);
                }
            });
            assertEquals(Arrays.asList("a", "b"), result);
            assertEquals(Arrays.asList("has:a", "get:a", "has:null", "has:b", "has:nested", "get:nested", "get:b"), calls);
        } finally { ServerSnapshotBridge.end(); }
        assertTrue(result.isEmpty());
    }
    @Test public void callbackMutationHasTheSameBulkTraversalAndFailureAsTheOriginalStream() {
        final List<Object> original = new ArrayList<Object>(Arrays.asList("a", "b", "c"));
        final List<String> expected = new ArrayList<String>();
        try {
            original.stream().filter(value -> {
                expected.add("has:" + value);
                if ("a".equals(value)) original.remove("b");
                return value != null;
            }).map(value -> { expected.add("get:" + value); return value; }).collect(java.util.stream.Collectors.toList());
            fail("Expected concurrent modification");
        } catch (java.util.ConcurrentModificationException expectedFailure) { }
        final List<Object> changed = new ArrayList<Object>(Arrays.asList("a", "b", "c"));
        final List<String> actual = new ArrayList<String>();
        ServerSnapshotBridge.begin();
        try {
            try {
                ServerSnapshotBridge.processors(changed, new ServerProcessorAccess() {
                    public boolean ice$hasProcessor(Object value) {
                        actual.add("has:" + value);
                        if ("a".equals(value)) changed.remove("b");
                        return value != null;
                    }
                    public Object ice$getProcessor(Object value) { actual.add("get:" + value); return value; }
                });
                fail("Expected concurrent modification");
            } catch (java.util.ConcurrentModificationException expectedFailure) { }
            assertEquals(expected, actual);
        } finally { ServerSnapshotBridge.end(); }
    }
}
