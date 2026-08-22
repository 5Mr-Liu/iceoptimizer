package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, bounded representation of an OptiFine shaders.properties file. */
public final class ShaderPackProperties {
    private final Map<String, String> values;
    private final int permutationDirectives;

    ShaderPackProperties(Map<String, String> values, int permutationDirectives) {
        this.values = Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(values));
        this.permutationDirectives = permutationDirectives;
    }

    public String get(String key) { return values.get(key); }
    public boolean contains(String key) { return values.containsKey(key); }
    public int size() { return values.size(); }
    public int getPermutationDirectives() { return permutationDirectives; }
    public Map<String, String> asMap() { return values; }
}
