package dev.rlcraft.ice.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class OptimizerTargetCatalog {
    private static final Logger LOGGER = LogManager.getLogger("ICE Optimizer Catalog");
    private static final String RESOURCE = "/optimizer-targets.properties";
    private static final Map<String, TargetSpec> TARGETS = load();

    private OptimizerTargetCatalog() {
    }

    static TargetSpec find(String transformedName) {
        return TARGETS.get(transformedName);
    }

    private static Map<String, TargetSpec> load() {
        InputStream input = OptimizerTargetCatalog.class.getResourceAsStream(RESOURCE);
        if (input == null) return Collections.emptyMap();
        Properties properties = new Properties();
        try {
            properties.load(input);
        } catch (IOException error) {
            LOGGER.error("无法读取 ICE 优化目标目录", error);
            return Collections.emptyMap();
        } finally {
            try { input.close(); } catch (IOException ignored) { }
        }
        Map<String, TargetSpec> result = new HashMap<String, TargetSpec>();
        int count = integer(properties.getProperty("target.count"), 0);
        for (int i = 0; i < count; i++) {
            String prefix = "target." + i + ".";
            String className = value(properties, prefix + "class");
            String module = value(properties, prefix + "module");
            String adapter = value(properties, prefix + "adapter");
            if (className.isEmpty() || module.isEmpty()) continue;
            Set<String> fingerprints = new HashSet<String>();
            String configured = value(properties, prefix + "sha256");
            if (!configured.isEmpty()) {
                for (String fingerprint : configured.split(",")) {
                    String clean = fingerprint.trim().toLowerCase();
                    if (clean.length() == 64) fingerprints.add(clean);
                }
            }
            result.put(className, new TargetSpec(className, module, adapter, fingerprints));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? "" : value.trim();
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return fallback; }
    }
}
