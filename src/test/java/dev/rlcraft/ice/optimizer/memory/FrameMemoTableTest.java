package dev.rlcraft.ice.optimizer.memory;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class FrameMemoTableTest {
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
