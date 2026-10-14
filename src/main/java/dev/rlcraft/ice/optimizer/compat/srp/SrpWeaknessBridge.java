package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Config-array snapshots only; damage and entity state are never cached. */
public final class SrpWeaknessBridge {
    private static final int MODULE = OptimizationModule.SRP_WEAKNESS_RULES.ordinal();
    private static final java.util.concurrent.atomic.AtomicReferenceArray<Rules> CACHE =
        new java.util.concurrent.atomic.AtomicReferenceArray<Rules>(3);
    private static volatile boolean activated;

    private SrpWeaknessBridge() { }

    public static void invalidate() {
        for (int i = 0; i < 3; i++) CACHE.set(i, null);
    }

    public static float apply(float damage, String source, String[] entries, int category) {
        if (!OptimizerBridge.isEnabled(MODULE)) return original(damage, source, entries);
        Rules rules = CACHE.get(category);
        // SRP's config loader publishes a new array when its settings are reloaded.
        if (rules == null || rules.source != entries) {
            rules = new Rules(entries);
            CACHE.set(category, rules);
        }
        float result = rules.apply(damage, source);
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRP 伤害弱点名称索引与数值解析复用");
        }
        return result;
    }

    static float original(float damage, String source, String[] entries) {
        for (String entry : entries) {
            if (entry == null) continue;
            String[] parts = entry.split(";");
            if (parts[0].equals(source)) damage *= Float.parseFloat(parts[1]);
        }
        return damage;
    }

    static final class Rules {
        final String[] source;
        final Map<String, Group> groups = new HashMap<String, Group>();
        final boolean emptySplit;

        Rules(String[] source) {
            this.source = source;
            boolean invalid = false;
            for (String entry : source) {
                if (entry == null) continue;
                String[] parts = entry.split(";");
                // String.split discards trailing empty tokens. Such a rule fails
                // for every source, but earlier matching numeric failures win.
                if (parts.length == 0) { invalid = true; break; }
                Group group = groups.get(parts[0]);
                if (group == null) {
                    group = new Group();
                    groups.put(parts[0], group);
                }
                group.parts.add(parts);
            }
            emptySplit = invalid;
        }

        float apply(float damage, String name) {
            Group group = groups.get(name);
            if (group != null) {
                for (float multiplier : group.multipliers()) damage *= multiplier;
            }
            if (emptySplit) throw new ArrayIndexOutOfBoundsException(0);
            return damage;
        }
    }

    private static final class Group {
        final List<String[]> parts = new ArrayList<String[]>();
        volatile float[] parsed;

        float[] multipliers() {
            float[] result = parsed;
            if (result == null) {
                result = new float[parts.size()];
                for (int i = 0; i < result.length; i++) {
                    result[i] = Float.parseFloat(parts.get(i)[1]);
                }
                parsed = result;
            }
            return result;
        }
    }
}
