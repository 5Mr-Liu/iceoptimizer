package dev.rlcraft.ice.optimizer.compat.konkrete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

    @Test
    public void lifecycleResetDropsTheRetainedLocaleAndReverseMaps()
        throws Exception {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("key", "value");
        Map<String, String> reverse =
            KonkreteLocaleBridge.buildReverseForTest(source);
        Class<?> cacheType = Class.forName(
            KonkreteLocaleBridge.class.getName() + "$Cache");
        Constructor<?> constructor = cacheType.getDeclaredConstructor(
            Map.class, long.class, int.class, Map.class);
        constructor.setAccessible(true);
        Object retained = constructor.newInstance(source, Long.valueOf(7L),
            Integer.valueOf(1), reverse);
        Field cache = KonkreteLocaleBridge.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, retained);

        KonkreteLocaleBridge.reset();

        assertNull(cache.get(null));
    }
}
