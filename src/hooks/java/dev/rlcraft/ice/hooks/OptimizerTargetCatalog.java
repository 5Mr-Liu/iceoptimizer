package dev.rlcraft.ice.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class OptimizerTargetCatalog {
    private static final Logger LOGGER = LogManager.getLogger("ICE Optimizer Catalog");
    private static final String RESOURCE = "/optimizer-targets.properties";
    private static final Map<String, List<TargetSpec>> TARGETS = load();

    private OptimizerTargetCatalog() {
    }

    static TargetSpec find(String transformedName) {
        List<TargetSpec> targets = findAll(transformedName);
        return targets.isEmpty() ? null : targets.get(0);
    }

    static List<TargetSpec> findAll(String transformedName) {
        List<TargetSpec> targets = TARGETS.get(transformedName);
        return targets == null ? Collections.<TargetSpec>emptyList() : targets;
    }

    private static Map<String, List<TargetSpec>> load() {
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
        Map<String, List<TargetSpec>> mutable = new HashMap<String, List<TargetSpec>>();
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
            List<TargetSpec> targets = mutable.get(className);
            if (targets == null) {
                targets = new ArrayList<TargetSpec>();
                mutable.put(className, targets);
            }
            targets.add(new TargetSpec(className, module, adapter, fingerprints));
        }
        Map<String, List<TargetSpec>> result = new HashMap<String, List<TargetSpec>>();
        for (Map.Entry<String, List<TargetSpec>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(
                new ArrayList<TargetSpec>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? "" : value.trim();
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) {
            HookFatalErrors.rethrowIfFatal(ignored);
            return fallback;
        }
    }
}
