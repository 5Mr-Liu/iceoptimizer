package dev.rlcraft.ice.optimizer.compat.bettercaves;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public class BetterCavesThresholdMapTest {
    @Test
    public void matchesTheOriginalFloatFormulaBitForBit() {
        assertFormula(80, 5, 60, 0.37F);
        assertFormula(255, 1, 128, -0.125F);
        assertFormula(32, 32, 32, 0.5F);
        assertFormula(7, 9, 8, 1.0F);
    }

    @Test
    public void exposesTheSameContiguousMapContract() {
        BetterCavesThresholdMap map = new BetterCavesThresholdMap(12, 10, 11, 0.4F);
        assertEquals(3, map.size());
        assertTrue(map.containsKey(Integer.valueOf(10)));
        assertTrue(map.containsKey(Integer.valueOf(12)));
        assertFalse(map.containsKey(Integer.valueOf(9)));
        assertNull(map.get(Integer.valueOf(13)));
        int expected = 10;
        for (Map.Entry<Integer, Float> entry : map.entrySet()) {
            assertEquals(expected++, entry.getKey().intValue());
        }
    }

    private static void assertFormula(int top, int bottom, int surface, float base) {
        BetterCavesThresholdMap map = new BetterCavesThresholdMap(top, bottom, surface, base);
        assertEquals(Math.max(0, top - bottom + 1), map.size());
        for (int y = bottom; y <= top; y++) {
            float expected = base;
            if (y >= surface) {
                expected *= 1.0F + 0.3F * ((float) (y - surface) / (float) (top - surface));
            }
            Float actual = map.get(Integer.valueOf(y));
            assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual.floatValue()));
            if (y == Integer.MAX_VALUE) break;
        }
    }
}
