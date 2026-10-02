package dev.rlcraft.ice.optimizer.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class FrameMemoTableTest {
    @Test
    public void nullResultsRemainUncachedAndLaterValuesCanBeMemoized() {
        FrameMemoTable<String> table = new FrameMemoTable<String>(16);
        assertNull(table.getOrCompute(1L, 99L, key -> null));
        assertEquals(0, table.size());
        assertEquals("ready", table.getOrCompute(1L, 99L, key -> "ready"));
        assertEquals("ready", table.getOrCompute(1L, 99L, key -> {
            throw new AssertionError("cached value was recomputed");
        }));
        table.clear();
        assertNull(table.getOrCompute(1L, 99L, key -> null));
    }

    @Test
    public void exactKeysAreReusedOnlyInsideSameGeneration() {
        FrameMemoTable<String> table = new FrameMemoTable<String>(16);
        AtomicInteger loads = new AtomicInteger();
        assertEquals("value-1", table.getOrCompute(10L, 99L, key -> "value-" + loads.incrementAndGet()));
        assertEquals("value-1", table.getOrCompute(10L, 99L, key -> "value-" + loads.incrementAndGet()));
        assertEquals("value-2", table.getOrCompute(11L, 99L, key -> "value-" + loads.incrementAndGet()));
        assertEquals(2, loads.get());
    }
}
