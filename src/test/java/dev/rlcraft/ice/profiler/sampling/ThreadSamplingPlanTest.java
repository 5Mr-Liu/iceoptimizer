package dev.rlcraft.ice.profiler.sampling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ThreadSamplingPlanTest {
    @Test
    public void deepSamplingAlwaysKeepsMainThreadsAndRotatesWorkers() {
        ThreadDescriptor client = descriptor(1L, ThreadRole.CLIENT_MAIN);
        ThreadDescriptor server = descriptor(2L, ThreadRole.SERVER_MAIN);
        List<ThreadDescriptor> descriptors = new ArrayList<ThreadDescriptor>();
        descriptors.add(client);
        descriptors.add(server);
        for (int i = 0; i < 10; i++) {
            descriptors.add(descriptor(10L + i, ThreadRole.CHUNK_WORKER));
        }

        ThreadSamplingPlan plan = ThreadSamplingPlan.create(descriptors);
        assertEquals(12, plan.fullBatch().size());
        assertEquals(3, plan.deepBatchCount());
        for (int batch = 0; batch < plan.deepBatchCount(); batch++) {
            ThreadDescriptor[] selected = plan.deepBatch(batch).descriptors();
            assertSame(client, selected[0]);
            assertSame(server, selected[1]);
            assertEquals(batch == 2 ? 4 : 6, selected.length);
        }
    }

    @Test
    public void primitiveCounterTableHandlesInvalidAndMonotonicSamples() {
        ThreadSampler.LongCounterTable table = new ThreadSampler.LongCounterTable();
        assertEquals(0L, table.updateAndDelta(7L, 100L));
        assertEquals(25L, table.updateAndDelta(7L, 125L));
        assertEquals(0L, table.updateAndDelta(7L, -1L));
        assertEquals(0L, table.updateAndDelta(7L, 200L));
        assertEquals(5L, table.updateAndDelta(7L, 205L));
        for (long id = 8L; id < 100L; id++) {
            assertEquals(0L, table.updateAndDelta(id, id * 10L));
            assertEquals(3L, table.updateAndDelta(id, id * 10L + 3L));
        }
    }

    private static ThreadDescriptor descriptor(long id, ThreadRole role) {
        return new ThreadDescriptor(id, role.name() + '-' + id, role);
    }
}
