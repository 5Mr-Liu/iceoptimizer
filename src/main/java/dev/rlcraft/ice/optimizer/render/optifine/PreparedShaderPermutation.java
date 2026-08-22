package dev.rlcraft.ice.optimizer.render.optifine;

public final class PreparedShaderPermutation {
    private final ShaderPermutationKey key;
    private final PreprocessedShader vertex;
    private final PreprocessedShader geometry;
    private final PreprocessedShader fragment;
    private final ShaderPackProperties properties;

    PreparedShaderPermutation(ShaderPermutationKey key,
                              PreprocessedShader vertex,
                              PreprocessedShader fragment,
                              ShaderPackProperties properties) {
        this(key, vertex, null, fragment, properties);
    }

    PreparedShaderPermutation(ShaderPermutationKey key,
                              PreprocessedShader vertex,
                              PreprocessedShader geometry,
                              PreprocessedShader fragment,
                              ShaderPackProperties properties) {
        if (key == null || vertex == null || fragment == null
            || properties == null) {
            throw new IllegalArgumentException("prepared shader permutation");
        }
        this.key = key;
        this.vertex = vertex;
        this.geometry = geometry;
        this.fragment = fragment;
        this.properties = properties;
    }

    public ShaderPermutationKey getKey() { return key; }
    public PreprocessedShader getVertex() { return vertex; }
    public PreprocessedShader getGeometry() { return geometry; }
    public PreprocessedShader getFragment() { return fragment; }
    public ShaderPackProperties getProperties() { return properties; }
}
