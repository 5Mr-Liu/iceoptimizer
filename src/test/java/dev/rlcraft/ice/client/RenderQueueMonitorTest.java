package dev.rlcraft.ice.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class RenderQueueMonitorTest {
    @Test
    public void parsesVanillaDispatcherDebugInfo() {
        assertArrayEquals(new int[] { 37, 4 },
            RenderQueueMonitor.parseDebugInfo("pC: 037, pU: 4, aB: 2"));
        assertArrayEquals(new int[] { 3, -1 },
            RenderQueueMonitor.parseDebugInfo("pC: 003, single-threaded"));
    }

    @Test
    public void readsCollectionsAndSizeCompatibleRuntimeQueues() {
        assertEquals(3, RenderQueueMonitor.size(Arrays.asList("a", "b", "c")));
        assertEquals(7, RenderQueueMonitor.size(new SizeOnlyQueue()));
    }

    @Test
    public void renderQueueIncludesUpstreamChunksAwaitingDispatch() {
        assertEquals(17, RenderQueueMonitor.combinedPending(5, 12));
        assertEquals(5, RenderQueueMonitor.combinedPending(5, -1));
        assertEquals(12, RenderQueueMonitor.combinedPending(-1, 12));
        assertEquals(Integer.MAX_VALUE, RenderQueueMonitor.combinedPending(
            Integer.MAX_VALUE, 1));
    }

    public static final class SizeOnlyQueue {
        public int size() { return 7; }
    }
}
