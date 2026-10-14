package dev.rlcraft.ice.optimizer.compat.gl;

import java.nio.FloatBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EarlyModelLightingStateTest {
    @Test public void wrapperUpdatesKeepPerTextureTexgenAndLightModesIndependent() {
        int[] values = new int[20]; int[] texgen = new int[8];
        EarlyModelLightingState.seed(values, texgen, 0);
        EarlyModelLightingState.texGenCoord(0, true);
        EarlyModelLightingState.activeTexture(33985);
        EarlyModelLightingState.texGenCoord(1, true);
        EarlyModelLightingState.activeTexture(33984);
        EarlyModelLightingState.texGenCoord(0, false);
        EarlyModelLightingState.light(3, true);
        EarlyModelLightingState.capability(32826, true);
        EarlyModelLightingState.colorMaterial(1032, 5634);
        EarlyModelLightingState.lightModel(2898, FloatBuffer.wrap(new float[] {1}));
        EarlyModelLightingState.lightModel(33272, 33274);
        assertTrue(EarlyModelLightingState.copyTo(values));
        assertEquals(2, values[11]); assertEquals(1, values[15]);
        assertEquals(1, values[2]); assertEquals(1, values[3]); assertEquals(1, values[5]);
        assertEquals(1032, values[7]); assertEquals(5634, values[8]);
        EarlyModelLightingState.activeTexture(33985);
        EarlyModelLightingState.texGenCoord(1, false);
        assertTrue(EarlyModelLightingState.copyTo(values)); assertEquals(0, values[11]);
    }

    @Test public void callbackInvalidationCannotBeRepairedByAnIncompleteWrapperUpdate() {
        int[] values = new int[20];
        EarlyModelLightingState.seed(values, new int[8], 0);
        EarlyGlStateTracker.invalidate();
        EarlyModelLightingState.light(0, true);
        assertFalse(EarlyModelLightingState.copyTo(values));
        EarlyModelLightingState.seed(values, new int[8], 0);
        assertTrue(EarlyModelLightingState.isKnown());
        EarlyGlStateTracker.beginProbe();
        assertFalse(EarlyModelLightingState.isKnown());
    }
}
