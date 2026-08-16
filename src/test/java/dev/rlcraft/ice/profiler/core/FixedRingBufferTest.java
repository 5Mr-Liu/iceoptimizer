package dev.rlcraft.ice.profiler.core;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class FixedRingBufferTest {
    @Test
    public void overwritesOldestWithoutGrowing() {
        FixedRingBuffer<Integer> buffer = new FixedRingBuffer<Integer>(3);
        buffer.add(1); buffer.add(2); buffer.add(3); buffer.add(4); buffer.add(5);
        assertEquals(Arrays.asList(3, 4, 5), buffer.snapshot());
        assertEquals(3, buffer.size());
        assertEquals(2L, buffer.overwrittenCount());
    }
}
