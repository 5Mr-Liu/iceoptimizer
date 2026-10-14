package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import ivorius.ivtoolkit.random.BlurredValueField;
import java.util.Random;
import org.junit.*;
import static org.junit.Assert.*;

public class RecurrentCloudFieldTest {
    private boolean paused;
    @Before public void enable() {
        paused = OptimizerRegistry.isRuntimePaused(); OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_CLOUD_FIELD).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_CLOUD_FIELD).patchInstalled("cloud", "test");
    }
    @After public void restore() {
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_CLOUD_FIELD).configure(false, 3);
        OptimizerRegistry.setRuntimePaused(paused);
    }
    @Test public void frozenRandomFieldsRemainBitExactIncludingFullIntegerCoordinatesAndGrowth() {
        for (long seed : new long[] { 0, -1, 817723 }) {
            BlurredValueField original = new BlurredValueField(24, 17, 13);
            RecurrentCloudField cached = new RecurrentCloudField(24, 17, 13);
            Random left = new Random(seed), right = new Random(seed);
            for (int i = 0; i < 200; i++) {
                double a = 1 + (left.nextFloat() - left.nextFloat()) * 0.5;
                double b = 1 + (right.nextFloat() - right.nextFloat()) * 0.5;
                assertEquals(original.addValue(a, left), cached.addValue(b, right));
            }
            assertEquals(left.nextLong(), right.nextLong());
            Random queries = new Random(seed + 7);
            for (int i = 0; i < 1200; i++) {
                int x = i < 3 ? Integer.MIN_VALUE + i : queries.nextInt(80) - 20;
                int y = i < 3 ? Integer.MAX_VALUE - i : queries.nextInt(80) - 20;
                int z = queries.nextInt(80) - 20;
                long expected = Double.doubleToRawLongBits(original.getValue(x, y, z));
                assertEquals(expected, Double.doubleToRawLongBits(cached.ice$getCloudValue(x, y, z)));
                assertEquals(expected, Double.doubleToRawLongBits(cached.ice$getCloudValue(x, y, z)));
            }
        }
    }
    @Test public void repeatedCoordinateSkipsInterpolationAndF6RunsOriginalAgain() {
        RecurrentCloudField field = new RecurrentCloudField(10, 10, 10);
        CountingValue value = new CountingValue(0.75, new int[] { 2, 3, 4 });
        field.addValue(value);
        double first = field.ice$getCloudValue(5, 6, 7);
        int calls = value.calls; assertTrue(calls > 0);
        for (int i = 0; i < 100; i++) assertEquals(first, field.ice$getCloudValue(5, 6, 7), 0);
        assertEquals(calls, value.calls);
        OptimizerRegistry.setRuntimePaused(true);
        assertEquals(first, field.ice$getCloudValue(5, 6, 7), 0); assertTrue(value.calls > calls);
        calls = value.calls; OptimizerRegistry.setRuntimePaused(false);
        assertEquals(first, field.ice$getCloudValue(5, 6, 7), 0); assertEquals(calls, value.calls);
    }
    @Test public void independentBuildsNeverShareSamplesAndEmptyFieldPreservesOriginalValue() {
        RecurrentCloudField first = new RecurrentCloudField(8, 8, 8), second = new RecurrentCloudField(8, 8, 8);
        assertEquals(Double.doubleToRawLongBits(new BlurredValueField(8, 8, 8).getValue(1, 2, 3)),
            Double.doubleToRawLongBits(first.ice$getCloudValue(1, 2, 3)));
        second.addValue(new BlurredValueField.Value(8, new int[] { 0, 0, 0 }));
        assertEquals(8, second.ice$getCloudValue(1, 2, 3), 0);
    }
    private static final class CountingValue extends BlurredValueField.Value {
        int calls;
        CountingValue(double value, int[] position) { super(value, position); }
        @Override public int[] pos() { calls++; return super.pos(); }
    }
}
