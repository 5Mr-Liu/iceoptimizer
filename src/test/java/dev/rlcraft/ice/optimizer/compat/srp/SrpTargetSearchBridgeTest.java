package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SrpTargetSearchBridgeTest {
    private boolean previousPause;

    @Before public void enable() {
        previousPause = OptimizerRegistry.isRuntimePaused();
        OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.SRP_TARGET_SEARCH).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_TARGET_SEARCH).patchInstalled("target-ai", "test");
    }

    @After public void restore() {
        OptimizerRegistry.breaker(OptimizationModule.SRP_TARGET_SEARCH).configure(false, 3);
        OptimizerRegistry.setRuntimePaused(previousPause);
    }

    @Test
    public void sequentialListUsesExactlyOneComparisonPerRemainingCandidateAndStableTies() {
        List<Item> values = new LinkedList<Item>();
        for (int i = 0; i < 127; i++) values.add(new Item((i * 29 + 11) % 31));
        List<Item> original = new ArrayList<Item>(values);
        CountingComparator comparator = new CountingComparator();
        List<Item> sorted = new ArrayList<Item>(values);
        Collections.sort(sorted, comparator);
        comparator.comparisons = 0;
        SrpTargetSearchBridge.selectFirst(values, comparator);
        assertSame(sorted.get(0), values.get(0));
        assertEquals(values.size() - 1, comparator.comparisons);
        // Only the selected first minimum changes position, preserving every candidate.
        int selected = original.indexOf(sorted.get(0));
        Collections.swap(original, 0, selected);
        assertEquals(original, values);
    }

    @Test
    public void emptyAndSingletonListsDoNotPerformAFullSort() {
        SortCountingList<Item> empty = new SortCountingList<Item>();
        SortCountingList<Item> single = new SortCountingList<Item>();
        Item item = new Item(2);
        single.add(item);
        CountingComparator comparator = new CountingComparator();
        SrpTargetSearchBridge.selectFirst(empty, comparator);
        SrpTargetSearchBridge.selectFirst(single, comparator);
        assertEquals(0, empty.sorts);
        assertEquals(0, single.sorts);
        assertEquals(0, comparator.comparisons);
        assertSame(item, single.get(0));
    }

    @Test
    public void runtimePauseKeepsOriginalFullSortIncludingSingleton() {
        OptimizerRegistry.setRuntimePaused(true);
        List<Integer> values = new LinkedList<Integer>(Arrays.asList(7, 2, 5, 1, 3));
        SrpTargetSearchBridge.selectFirst(values, Comparator.<Integer>naturalOrder());
        assertEquals(Arrays.asList(1, 2, 3, 5, 7), values);
        SortCountingList<Item> single = new SortCountingList<Item>();
        single.add(new Item(4));
        SrpTargetSearchBridge.selectFirst(single, new CountingComparator());
        assertEquals(1, single.sorts);
    }

    private static final class Item {
        final int distance;
        Item(int distance) { this.distance = distance; }
    }

    private static final class CountingComparator implements Comparator<Item> {
        int comparisons;
        @Override public int compare(Item left, Item right) {
            comparisons++;
            return Integer.compare(left.distance, right.distance);
        }
    }

    private static final class SortCountingList<T> extends ArrayList<T> {
        int sorts;
        @Override public void sort(Comparator<? super T> comparator) {
            sorts++;
            super.sort(comparator);
        }
    }
}
