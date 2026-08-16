package dev.rlcraft.ice.optimizer.compat.konkrete;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class KonkreteLocaleBridgeTest {
    @Test
    public void reverseIndexPreservesFirstSourceIterationKeyForDuplicates() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("first.key", "Same text");
        source.put("second.key", "Same text");
        source.put("other.key", "Other text");
        Map<String, String> reverse = KonkreteLocaleBridge.buildReverseForTest(source);
        assertEquals("first.key", reverse.get("Same text"));
        assertEquals("other.key", reverse.get("Other text"));
    }
}
