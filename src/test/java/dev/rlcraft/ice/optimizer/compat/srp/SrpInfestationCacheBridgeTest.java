package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import org.junit.Test;

public final class SrpInfestationCacheBridgeTest {
    @Test public void preservesSubstringRulesAndRefreshesOnConfigArrayReplacement() {
        OptimizerRegistry.breaker(OptimizationModule.SRP_INFESTATION_CACHE).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_INFESTATION_CACHE).patchInstalled("fixture", "test");
        try {
            String[] rules = { "ore", "minecraft:bedrock" };
            for (int repeat = 0; repeat < 3; repeat++) {
                assertTrue(SrpInfestationCacheBridge.matches(rules, "mod:copper_ore"));
                assertFalse(SrpInfestationCacheBridge.matches(rules, "minecraft:stone"));
            }
            String[] reloaded = { "stone" };
            assertTrue(SrpInfestationCacheBridge.matches(reloaded, "minecraft:stone"));
            assertFalse(SrpInfestationCacheBridge.matches(reloaded, "mod:copper_ore"));
            assertTrue(SrpInfestationCacheBridge.matches(new String[] { "" }, "any:block"));
            assertFalse(SrpInfestationCacheBridge.matches(new String[0], "any:block"));
            // Explicit reload invalidation also supports config editors mutating arrays in place.
            reloaded[0] = "ore";
            SrpInfestationCacheBridge.invalidate();
            assertFalse(SrpInfestationCacheBridge.matches(reloaded, "minecraft:stone"));
        } finally {
            SrpInfestationCacheBridge.invalidate();
            OptimizerRegistry.breaker(OptimizationModule.SRP_INFESTATION_CACHE).configure(false, 3);
        }
    }

    @Test public void runtimePauseUsesLiveRulesWithoutCachedResults() {
        OptimizerRegistry.breaker(OptimizationModule.SRP_INFESTATION_CACHE).configure(false, 3);
        String[] rules = { "ore" };
        assertFalse(SrpInfestationCacheBridge.matches(rules, "minecraft:stone"));
        rules[0] = "stone";
        assertTrue(SrpInfestationCacheBridge.matches(rules, "minecraft:stone"));
    }
}
