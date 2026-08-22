package dev.rlcraft.ice.optimizer.render.optifine;

public final class ShaderPermutationKey {
    private final String packId;
    private final String program;
    private final String permutation;
    private final long resourceGeneration;
    private final long shaderGeneration;
    private final long sourceHash;

    public ShaderPermutationKey(String packId, String program, String permutation,
                                long resourceGeneration, long shaderGeneration,
                                long sourceHash) {
        if (!bounded(packId, 256) || !bounded(program, 256)
            || !bounded(permutation, 1024) || resourceGeneration <= 0L
            || shaderGeneration <= 0L) throw new IllegalArgumentException("shader key");
        this.packId = packId;
        this.program = program;
        this.permutation = permutation;
        this.resourceGeneration = resourceGeneration;
        this.shaderGeneration = shaderGeneration;
        this.sourceHash = sourceHash;
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isEmpty() && value.length() <= maximum
            && value.indexOf('\0') < 0;
    }

    public long getResourceGeneration() { return resourceGeneration; }
    public long getShaderGeneration() { return shaderGeneration; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ShaderPermutationKey)) return false;
        ShaderPermutationKey other = (ShaderPermutationKey) value;
        return resourceGeneration == other.resourceGeneration
            && shaderGeneration == other.shaderGeneration && sourceHash == other.sourceHash
            && packId.equals(other.packId) && program.equals(other.program)
            && permutation.equals(other.permutation);
    }

    @Override public int hashCode() {
        int result = packId.hashCode();
        result = 31 * result + program.hashCode();
        result = 31 * result + permutation.hashCode();
        result = 31 * result + (int) (resourceGeneration ^ (resourceGeneration >>> 32));
        result = 31 * result + (int) (shaderGeneration ^ (shaderGeneration >>> 32));
        return 31 * result + (int) (sourceHash ^ (sourceHash >>> 32));
    }
}
