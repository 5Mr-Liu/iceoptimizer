package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

/** Stable first-minimum selection for an SRP list that never escapes its AI method. */
public final class SrpTargetSearchBridge {
    private static final int MODULE = OptimizationModule.SRP_TARGET_SEARCH.ordinal();
    private static volatile boolean activated;

    private SrpTargetSearchBridge() {
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void selectFirst(List values, Comparator comparator) {
        if (!OptimizerBridge.isEnabled(MODULE)) {
            Collections.sort(values, comparator);
            return;
        }
        if (values.size() < 2) return;
        ListIterator iterator = values.listIterator();
        int bestIndex = 0;
        Object best = iterator.next();
        while (iterator.hasNext()) {
            int index = iterator.nextIndex();
            Object candidate = iterator.next();
            if (comparator.compare(candidate, best) < 0) {
                best = candidate;
                bestIndex = index;
            }
        }
        if (bestIndex != 0) values.set(bestIndex, values.set(0, best));
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRP 最近目标由完整排序改为稳定线性选择");
        }
    }
}
