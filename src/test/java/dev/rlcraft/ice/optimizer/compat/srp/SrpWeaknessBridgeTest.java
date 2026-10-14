package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SrpWeaknessBridgeTest {
    @Before public void enable() {
        OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.SRP_WEAKNESS_RULES).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_WEAKNESS_RULES).patchInstalled("fixture", "test");
    }

    @After public void cleanup() {
        OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.SRP_WEAKNESS_RULES).configure(false, 3);
    }

    @Test public void duplicateRulesPreserveEveryFloatRoundingStep() {
        Random random = new Random(41921);
        for (int trial = 0; trial < 500; trial++) {
            String[] entries = new String[32];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = (i % 3 == 0 ? "other" : "hit") + ";"
                    + Float.toString(random.nextFloat() * 2.0f - 0.2f);
            }
            entries[3] = null;
            float amount = Float.intBitsToFloat(random.nextInt());
            assertBits(SrpWeaknessBridge.original(amount, "hit", entries),
                SrpWeaknessBridge.apply(amount, "hit", entries, 0));
            assertBits(SrpWeaknessBridge.original(amount, "hit", entries),
                SrpWeaknessBridge.apply(amount, "hit", entries, 0));
        }
    }

    @Test public void malformedRulesFailOnlyWhenOriginalWouldAndInOriginalOrder() {
        String[][] cases = {
            {"unmatched;bogus", "hit;2"}, {"unmatched", "hit;3"},
            {"hit;NaN", "hit;Infinity"}, {"hit;bad", ";;;"},
            {"hit;2", ";;;", "hit;bad"}, {"hit", "hit;bad"},
            {"", "hit;2"}, {";2", "hit;0"}, {"hit;;"},
            {"hit;2;ignored", "hit;-0.0"}, {";"}, {null, "hit; 2.5 "}
        };
        for (String[] entries : cases) {
            for (String source : new String[] {"hit", "unmatched", "", null}) {
                Object expected = outcome(entries, source, false);
                Object actual = outcome(entries, source, true);
                assertEquals(expected, actual);
            }
        }
    }

    @Test public void configReplacementAndPauseUseCurrentRules() {
        String[] first = {"hit;2", "hit;3"};
        assertEquals(42f, SrpWeaknessBridge.apply(7f, "hit", first, 1), 0f);
        String[] reloaded = {"hit;4"};
        assertEquals(28f, SrpWeaknessBridge.apply(7f, "hit", reloaded, 1), 0f);
        OptimizerRegistry.setRuntimePaused(true);
        reloaded[0] = "hit;5";
        assertEquals(35f, SrpWeaknessBridge.apply(7f, "hit", reloaded, 1), 0f);
        OptimizerRegistry.setRuntimePaused(false);
        SrpWeaknessBridge.invalidate();
        assertEquals(35f, SrpWeaknessBridge.apply(7f, "hit", reloaded, 1), 0f);
        assertEquals(14f, SrpWeaknessBridge.apply(7f, "hit", new String[] {"hit;2"}, 1), 0f);
    }

    private static Object outcome(String[] entries, String source, boolean compiled) {
        try {
            float result = compiled ? SrpWeaknessBridge.apply(7.25f, source, entries, 2)
                : SrpWeaknessBridge.original(7.25f, source, entries);
            return Float.floatToIntBits(result);
        } catch (RuntimeException error) {
            return error.getClass();
        }
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual));
    }
}
