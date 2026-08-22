package dev.rlcraft.ice.optimizer.render.optifine;

/** Receives bounded shader text only; implementations may perform GL compile/link. */
public interface ShaderCompilationDriver {
    ShaderCompilationResult compile(String vertexSource, String fragmentSource);

    /**
     * Geometry-aware entry used by the production certification drain.  A
     * driver that cannot compile geometry stages fails closed instead of
     * silently validating only two thirds of a permutation.
     */
    default ShaderCompilationResult compile(String vertexSource,
                                            String geometrySource,
                                            String fragmentSource) {
        if (geometrySource != null) {
            return new ShaderCompilationResult(false,
                "geometry shader compilation is unsupported by this driver");
        }
        return compile(vertexSource, fragmentSource);
    }

    default ShaderCompilationResult compile(String vertexSource,
                                            String geometrySource,
                                            String fragmentSource,
                                            int legacyProgramId) {
        return compile(vertexSource, geometrySource, fragmentSource);
    }
}
