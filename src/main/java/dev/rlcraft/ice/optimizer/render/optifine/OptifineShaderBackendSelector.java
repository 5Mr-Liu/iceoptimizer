package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;

/** Unknown or uncertified ShaderPack permutations always remain OF-compatible. */
public final class OptifineShaderBackendSelector {
    private final ShaderCertificationRegistry certifications;

    public OptifineShaderBackendSelector(ShaderCertificationRegistry certifications) {
        if (certifications == null) throw new IllegalArgumentException("certifications");
        this.certifications = certifications;
    }

    public RenderBackendId select(boolean shaderPackActive,
                                  boolean optifineRegionAvailable,
                                  ShaderPermutationKey permutation,
                                  boolean nativeProgramInstalled,
                                  boolean nativeVertexLayoutCompatible,
                                  boolean nativeBackendProfitable) {
        if (shaderPackActive) {
            if (permutation != null && certifications.isCertified(permutation)
                && nativeProgramInstalled && nativeVertexLayoutCompatible
                && nativeBackendProfitable) return RenderBackendId.ICE_NATIVE;
            return optifineRegionAvailable ? RenderBackendId.OF_COMPAT_REGION
                : RenderBackendId.LEGACY;
        }
        if (nativeBackendProfitable) return RenderBackendId.ICE_NATIVE;
        return optifineRegionAvailable ? RenderBackendId.OF_COMPAT_REGION
            : RenderBackendId.LEGACY;
    }
}
