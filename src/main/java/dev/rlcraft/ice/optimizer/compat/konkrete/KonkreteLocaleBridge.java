package dev.rlcraft.ice.optimizer.compat.konkrete;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.Locale;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

/**
 * Replaces Konkrete's two reflective field searches plus full locale-map scan
 * with one immutable reverse index per resource generation. The adapter keeps
 * Konkrete's original method and calls it whenever this bridge cannot prove the
 * standard vanilla Locale map shape.
 */
public final class KonkreteLocaleBridge {
    private static final int MODULE = OptimizationModule.KONKRETE_LOCALE_LOOKUP.ordinal();
    private static final Object FALLBACK = new Object();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static volatile Field localeField;
    private static volatile Field propertiesField;
    private static volatile Cache cache;

    private KonkreteLocaleBridge() {
    }

    /** Returns String/null on success and a private sentinel on safe fallback. */
    public static Object lookup(String value) {
        if (!OptimizerBridge.isEnabled(MODULE) || value == null) return FALLBACK;
        try {
            Map<?, ?> source = sourceMap();
            if (source == null || source.getClass() != HashMap.class) return FALLBACK;
            long generation = OptimizerBridge.currentResourceGeneration();
            Cache current = cache;
            int size = source.size();
            if (current == null || current.source != source
                || current.resourceGeneration != generation || current.sourceSize != size) {
                current = rebuild(source, generation, size);
                if (current == null) return FALLBACK;
            }
            String key = current.reverse.get(value);
            // Detect same-size replacement of the cached key's value. Normal
            // locale reloads are already covered by the resource generation.
            if (key != null && !value.equals(source.get(key))) {
                current = rebuild(source, generation, source.size());
                if (current == null) return FALLBACK;
                key = current.reverse.get(value);
            }
            if (ACTIVATED.compareAndSet(false, true)) {
                OptimizerBridge.activate(MODULE,
                    "Konkrete 本地化值已使用资源代际反向索引，移除逐次反射和全表扫描");
            }
            return key;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return FALLBACK;
        }
    }

    public static boolean isFallback(Object value) {
        return value == FALLBACK;
    }

    /** Releases the old Locale map and reverse index at a resource boundary. */
    public static synchronized void reset() {
        cache = null;
    }

    private static Cache rebuild(Map<?, ?> source, long generation, int expectedSize) {
        synchronized (KonkreteLocaleBridge.class) {
            Cache current = cache;
            int size = source.size();
            if (current != null && current.source == source
                && current.resourceGeneration == generation && current.sourceSize == size) {
                return current;
            }
            if (size != expectedSize) return null;
            HashMap<String, String> reverse = new HashMap<String, String>(
                Math.max(16, (int) (size / 0.75F) + 1));
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object rawKey = entry.getKey();
                Object rawValue = entry.getValue();
                if (!(rawKey instanceof String) || !(rawValue instanceof String)) return null;
                String key = (String) rawKey;
                String value = (String) rawValue;
                // Konkrete returns the first key in the source map's own
                // iteration order when translated values are duplicated.
                if (!reverse.containsKey(value)) reverse.put(value, key);
            }
            if (source.size() != size) return null;
            Cache built = new Cache(source, generation, size, reverse);
            cache = built;
            OptimizerBridge.success(MODULE);
            return built;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> sourceMap() throws IllegalAccessException {
        Field i18n = localeField;
        Field properties = propertiesField;
        if (i18n == null || properties == null) {
            synchronized (KonkreteLocaleBridge.class) {
                i18n = localeField;
                properties = propertiesField;
                if (i18n == null || properties == null) {
                    i18n = ObfuscationReflectionHelper.findField(I18n.class, "field_135054_a");
                    properties = ObfuscationReflectionHelper.findField(Locale.class, "field_135032_a");
                    localeField = i18n;
                    propertiesField = properties;
                }
            }
        }
        Object locale = i18n.get(null);
        Object map = locale == null ? null : properties.get(locale);
        return map instanceof Map ? (Map<?, ?>) map : null;
    }

    static Map<String, String> buildReverseForTest(Map<String, String> source) {
        HashMap<String, String> reverse = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (!reverse.containsKey(entry.getValue())) {
                reverse.put(entry.getValue(), entry.getKey());
            }
        }
        return reverse;
    }

    private static final class Cache {
        private final Map<?, ?> source;
        private final long resourceGeneration;
        private final int sourceSize;
        private final Map<String, String> reverse;

        private Cache(Map<?, ?> source, long resourceGeneration, int sourceSize,
                      Map<String, String> reverse) {
            this.source = source;
            this.resourceGeneration = resourceGeneration;
            this.sourceSize = sourceSize;
            this.reverse = reverse;
        }
    }
}
