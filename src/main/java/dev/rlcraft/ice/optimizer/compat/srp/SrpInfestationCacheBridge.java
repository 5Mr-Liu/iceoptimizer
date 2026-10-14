package dev.rlcraft.ice.optimizer.compat.srp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.concurrent.ConcurrentHashMap;

/** Caches only immutable registry-name matching, not world-dependent infestation eligibility. */
public final class SrpInfestationCacheBridge {
    private static final int MODULE = OptimizationModule.SRP_INFESTATION_CACHE.ordinal();
    private static final Cache<String[], ConcurrentHashMap<String, Boolean>> RULES =
        Caffeine.newBuilder().weakKeys().maximumSize(8L).build();
    private static volatile boolean activated;

    private SrpInfestationCacheBridge() { }

    public static boolean matches(String[] rules, String name) {
        if (!OptimizerBridge.isEnabled(MODULE)) return scan(rules, name);
        // Forge config reload replaces the array: the new identity gets its own table.
        ConcurrentHashMap<String, Boolean> matches = RULES.get(rules,
            ignored -> new ConcurrentHashMap<String, Boolean>());
        Boolean known = matches.get(name);
        if (known != null) return known.booleanValue();
        boolean result = scan(rules, name);
        matches.put(name, Boolean.valueOf(result));
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRP 感染名字规则已缓存命中和未命中结果");
        }
        return result;
    }

    private static boolean scan(String[] rules, String name) {
        for (String rule : rules) if (name.contains(rule)) return true;
        return false;
    }

    public static void invalidate() { RULES.invalidateAll(); }
}
