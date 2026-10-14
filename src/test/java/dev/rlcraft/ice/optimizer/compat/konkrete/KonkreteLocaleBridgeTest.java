package dev.rlcraft.ice.optimizer.compat.konkrete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class KonkreteLocaleBridgeTest {
    @Test
    public void publicLookupRebuildsChangedValuesAndDoesNotProbeNonstandardMaps()
        throws Exception {
        boolean enabled = OptimizerConfig.settings.enabled;
        boolean localeEnabled = OptimizerConfig.settings.konkreteLocaleLookup;
        Field localeAccess = bridgeField("localeField");
        Field propertiesAccess = bridgeField("propertiesField");
        Object oldLocaleAccess = localeAccess.get(null);
        Object oldPropertiesAccess = propertiesAccess.get(null);
        try {
            OptimizerConfig.settings.enabled = true;
            OptimizerConfig.settings.konkreteLocaleLookup = true;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            OptimizerRegistry.breaker(OptimizationModule.KONKRETE_LOCALE_LOOKUP)
                .patchInstalled("locale test", "test");
            localeAccess.set(null, LocaleHolder.class.getField("locale"));
            propertiesAccess.set(null, LocaleStub.class.getField("properties"));
            LocaleHolder.locale = new LocaleStub();
            LocaleHolder.locale.properties.put("first.key", "Shared text");
            LocaleHolder.locale.properties.put("second.key", "Shared text");
            KonkreteLocaleBridge.reset();

            String first = LocaleHolder.locale.properties.keySet().iterator().next();
            String second = "first.key".equals(first) ? "second.key" : "first.key";
            assertEquals(first, KonkreteLocaleBridge.lookup("Shared text"));
            // The map's size and identity stay fixed. A known changed value
            // must rebuild instead of returning the stale first key again.
            LocaleHolder.locale.properties.put(first, "Changed text");
            assertEquals(second, KonkreteLocaleBridge.lookup("Shared text"));
            assertEquals(first, KonkreteLocaleBridge.lookup("Changed text"));

            LocaleHolder.locale.properties.clear();
            assertNull(KonkreteLocaleBridge.lookup("Shared text"));

            LocaleHolder.locale.properties = new LinkedHashMap<String, String>(16, 0.75F, true);
            LocaleHolder.locale.properties.put("first.key", "Shared text");
            LocaleHolder.locale.properties.put("second.key", "Shared text");
            assertTrue(KonkreteLocaleBridge.isFallback(
                KonkreteLocaleBridge.lookup("Shared text")));
            assertEquals(Arrays.asList("first.key", "second.key"),
                new ArrayList<String>(LocaleHolder.locale.properties.keySet()));
        } finally {
            KonkreteLocaleBridge.reset();
            localeAccess.set(null, oldLocaleAccess);
            propertiesAccess.set(null, oldPropertiesAccess);
            LocaleHolder.locale = null;
            OptimizerConfig.settings.enabled = enabled;
            OptimizerConfig.settings.konkreteLocaleLookup = localeEnabled;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        }
    }

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

    private static Field bridgeField(String name) throws Exception {
        Field field = KonkreteLocaleBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static final class LocaleHolder {
        public static LocaleStub locale;
    }

    public static final class LocaleStub {
        public Map<String, String> properties = new HashMap<String, String>();
    }
}
