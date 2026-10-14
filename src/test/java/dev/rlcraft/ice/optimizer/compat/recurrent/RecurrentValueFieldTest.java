package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import ivorius.ivtoolkit.random.BlurredValueField;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecurrentValueFieldTest {
    private boolean paused;

    @Before public void enable() {
        paused = OptimizerRegistry.isRuntimePaused();
        OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_VALUE_WORKSPACE).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_VALUE_WORKSPACE).patchInstalled("ruins", "test");
    }

    @After public void restore() {
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_VALUE_WORKSPACE).configure(false, 3);
        OptimizerRegistry.setRuntimePaused(paused);
    }

    @Test public void realIvToolkitQueriesRemainBitExactAcrossTreesCoordinatesAndWorkspaceReuse() {
        for (int count : new int[] {0, 1, 2, 17, 128, 700}) {
            BlurredValueField original = new BlurredValueField(37, 23, 19);
            RecurrentValueField optimized = new RecurrentValueField(37, 23, 19);
            Random left = new Random(count), right = new Random(count);
            for (int i = 0; i < count; i++) {
                double a = left.nextDouble() * 4 - 2;
                double b = right.nextDouble() * 4 - 2;
                assertEquals(original.addValue(a, left), optimized.addValue(b, right));
            }
            assertEquals(left.nextLong(), right.nextLong());
            Random queries = new Random(99 + count);
            for (int i = 0; i < 1000; i++) {
                int[] position = {i % 37 == 0 ? Integer.MIN_VALUE : queries.nextInt(99) - 33,
                    i % 43 == 0 ? Integer.MAX_VALUE : queries.nextInt(89) - 31,
                    queries.nextInt(77) - 22};
                same(original.getValue(position), optimized.getValue(position));
            }
        }
    }

    @Test public void weightsSpecialValuesCallbackOrderAndF6MatchWithoutResultCaching() {
        for (double weight : new double[] {0, -1, 1, Double.MIN_VALUE, Double.MAX_VALUE,
                                          Double.POSITIVE_INFINITY, Double.NaN}) {
            StringBuilder expectedTrace = new StringBuilder(), actualTrace = new StringBuilder();
            BlurredValueField original = new BlurredValueField(9, 9, 9);
            RecurrentValueField optimized = new RecurrentValueField(9, 9, 9);
            TraceValue left = new TraceValue(expectedTrace, 0.25, weight, 1, 2, 3);
            TraceValue right = new TraceValue(actualTrace, 0.25, weight, 1, 2, 3);
            original.addValue(left); optimized.addValue(right);
            original.addValue(new TraceValue(expectedTrace, -0.0, -weight, 4, 5, 6));
            optimized.addValue(new TraceValue(actualTrace, -0.0, -weight, 4, 5, 6));
            for (boolean pause : new boolean[] {false, true, false}) {
                OptimizerRegistry.setRuntimePaused(pause);
                for (int[] position : new int[][] {{8, 7, 6}, {1, 2, 3}, {}}) {
                    expectedTrace.setLength(0); actualTrace.setLength(0);
                    same(original.getValue(position), optimized.getValue(position));
                    assertEquals(expectedTrace.toString(), actualTrace.toString());
                }
                left.currentValue = right.currentValue = 12.75;
                same(original.getValue(1, 2, 3), optimized.getValue(1, 2, 3));
            }
        }
    }

    @Test public void invalidCoordinatesAndThrowingCallbacksKeepExceptionsAndReleaseWorkspace() {
        BlurredValueField original = new BlurredValueField(9, 9, 9);
        RecurrentValueField optimized = new RecurrentValueField(9, 9, 9);
        same(original.getValue((int[]) null), optimized.getValue((int[]) null));
        TraceValue left = new TraceValue(new StringBuilder(), 0.75, 1, 1, 2, 3);
        TraceValue right = new TraceValue(new StringBuilder(), 0.75, 1, 1, 2, 3);
        original.addValue(left); optimized.addValue(right);
        for (int[] position : new int[][] {null, {8, 8, 8, 8}}) {
            assertEquals(failure(original, position).getClass(), failure(optimized, position).getClass());
            same(original.getValue(8, 8, 8), optimized.getValue(8, 8, 8));
        }
        for (int stage = 1; stage <= 3; stage++) {
            RuntimeException expected = new IllegalStateException("callback-" + stage);
            left.failure = right.failure = expected;
            left.stage = right.stage = stage;
            assertSame(expected, failure(original, new int[] {8, 8, 8}));
            assertSame(expected, failure(optimized, new int[] {8, 8, 8}));
            left.failure = right.failure = null;
            same(original.getValue(8, 8, 8), optimized.getValue(8, 8, 8));
        }
    }

    @Test public void nestedInterpolationUsesAnIndependentBorrowedWorkspace() {
        BlurredValueField original = new BlurredValueField(9, 9, 9);
        RecurrentValueField optimized = new RecurrentValueField(9, 9, 9);
        original.addValue(new NestedValue(original));
        optimized.addValue(new NestedValue(optimized));
        original.addValue(new BlurredValueField.Value(0.5, new int[] {4, 5, 6}));
        optimized.addValue(new BlurredValueField.Value(0.5, new int[] {4, 5, 6}));
        for (int i = 0; i < 30; i++) same(original.getValue(i, 7, 8), optimized.getValue(i, 7, 8));
    }

    @Test public void concurrentQueriesOfOneFieldDoNotShareWorkspaces() throws Exception {
        final BlurredValueField original = new BlurredValueField(37, 23, 19);
        final RecurrentValueField optimized = new RecurrentValueField(37, 23, 19);
        Random a = new Random(512), b = new Random(512);
        for (int i = 0; i < 160; i++) {
            original.addValue(a.nextDouble(), a);
            optimized.addValue(b.nextDouble(), b);
        }
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            List<Future<Void>> results = new ArrayList<Future<Void>>();
            for (int worker = 0; worker < 3; worker++) {
                final int seed = worker;
                results.add(workers.submit(new Callable<Void>() {
                    @Override public Void call() {
                        Random random = new Random(seed);
                        for (int i = 0; i < 500; i++) {
                            int[] pos = {random.nextInt(60), random.nextInt(50), random.nextInt(40)};
                            same(original.getValue(pos), optimized.getValue(pos));
                        }
                        return null;
                    }
                }));
            }
            for (Future<Void> result : results) result.get();
        } finally {
            workers.shutdownNow();
        }
    }

    private static void same(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static Throwable failure(BlurredValueField field, int[] position) {
        try { field.getValue(position); }
        catch (Throwable error) { return error; }
        throw new AssertionError("Expected interpolation failure");
    }

    private static final class TraceValue extends BlurredValueField.Value {
        final StringBuilder trace;
        double currentValue;
        RuntimeException failure;
        int stage;
        TraceValue(StringBuilder trace, double value, double weight, int... position) {
            super(value, weight, position);
            this.trace = trace;
            currentValue = value;
        }
        @Override public int[] pos() {
            trace.append('p');
            if (failure != null && stage == 1) throw failure;
            return super.pos();
        }
        @Override public double weight() {
            trace.append('w');
            if (failure != null && stage == 2) throw failure;
            return super.weight();
        }
        @Override public double value() {
            trace.append('v');
            if (failure != null && stage == 3) throw failure;
            return currentValue;
        }
    }

    private static final class NestedValue extends BlurredValueField.Value {
        final BlurredValueField owner;
        boolean inside;
        NestedValue(BlurredValueField owner) { super(0.75, new int[] {1, 2, 3}); this.owner = owner; }
        @Override public int[] pos() {
            if (!inside) {
                inside = true;
                try { owner.getValue(8, 8, 8); }
                finally { inside = false; }
            }
            return super.pos();
        }
    }
}
