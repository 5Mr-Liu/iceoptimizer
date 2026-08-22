package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LwjglConservativeDepthReducerTest {
    @Test
    public void positiveCeilDivisionHandlesIntegerBoundaryWithoutOverflow() {
        assertEquals(6_710_887,
            LwjglConservativeDepthReducer.ceilDivPositive(Integer.MAX_VALUE, 320));
        assertEquals(11_930_465,
            LwjglConservativeDepthReducer.ceilDivPositive(Integer.MAX_VALUE, 180));
        assertEquals(Integer.MAX_VALUE,
            LwjglConservativeDepthReducer.ceilDivPositive(Integer.MAX_VALUE, 1));
    }

    @Test
    public void positiveCeilDivisionPreservesExactAndPartialCells() {
        assertEquals(1, LwjglConservativeDepthReducer.ceilDivPositive(1, 16));
        assertEquals(1, LwjglConservativeDepthReducer.ceilDivPositive(16, 16));
        assertEquals(2, LwjglConservativeDepthReducer.ceilDivPositive(17, 16));
    }

    @Test(expected = IllegalArgumentException.class)
    public void positiveCeilDivisionRejectsNonPositiveValues() {
        LwjglConservativeDepthReducer.ceilDivPositive(0, 16);
    }

    @Test
    public void depthSceneSerialWrapsInsteadOfPinningAStaleCapture() {
        assertEquals(2L, LwjglDepthHistory.nextSceneSerial(1L));
        assertEquals(1L, LwjglDepthHistory.nextSceneSerial(Long.MAX_VALUE));
        assertEquals(1L, LwjglDepthHistory.nextSceneSerial(0L));
    }
}
