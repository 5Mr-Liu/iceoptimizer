package dev.rlcraft.ice.optimizer.render.terrain;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainBackendChoiceTest {
    @Test public void actualContextSelectsOnceAndIgnoresLaterCapabilities() {
        NativeTerrainBackendChoice modern = new NativeTerrainBackendChoice();
        assertEquals(43, modern.select(true, true));
        assertEquals(43, modern.select(false, false));
        NativeTerrainBackendChoice compatible = new NativeTerrainBackendChoice();
        assertEquals(21, compatible.select(false, true));
        assertEquals(21, compatible.select(true, true));
    }

    @Test public void unsupportedContextFailsWithoutPublishingAChoice() {
        NativeTerrainBackendChoice choice = new NativeTerrainBackendChoice();
        try { choice.select(false, false); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("2.1")); }
        assertFalse(choice.initialized());
        try { choice.get(); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("initialized")); }
    }
}
