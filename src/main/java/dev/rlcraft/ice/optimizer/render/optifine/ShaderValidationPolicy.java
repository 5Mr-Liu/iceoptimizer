package dev.rlcraft.ice.optimizer.render.optifine;

/**
 * Fail-closed text policy for the bounded offscreen A/B probe.  The probe may
 * execute a candidate twice, so shaders with externally visible storage or
 * synchronization side effects are never submitted to it.
 */
public final class ShaderValidationPolicy {
    private static final String[] FORBIDDEN_TOKENS = {
        "atomic_uint", "atomicCounter", "atomicCounterIncrement",
        "atomicCounterDecrement", "atomicCounterAddARB", "atomicAdd", "atomicMin",
        "atomicMax", "atomicAnd", "atomicOr", "atomicXor",
        "atomicExchange", "atomicCompSwap", "image1D", "image2D",
        "image3D", "imageCube", "imageCubeArray", "imageBuffer",
        "image1DArray", "image2DArray", "image2DRect", "image2DMS",
        "image2DMSArray", "iimage1D", "iimage2D", "iimage3D",
        "iimageCube", "iimageCubeArray", "iimageBuffer", "iimage1DArray",
        "iimage2DArray", "iimage2DRect", "iimage2DMS", "iimage2DMSArray",
        "uimage1D", "uimage2D", "uimage3D", "uimageCube",
        "uimageCubeArray", "uimageBuffer", "uimage1DArray",
        "uimage2DArray", "uimage2DRect", "uimage2DMS", "uimage2DMSArray",
        "imageLoad", "imageStore", "imageSize", "imageSamples",
        "imageAtomicAdd", "imageAtomicMin", "imageAtomicMax",
        "imageAtomicAnd", "imageAtomicOr", "imageAtomicXor",
        "imageAtomicExchange", "imageAtomicCompSwap", "memoryBarrier",
        "memoryBarrierAtomicCounter", "memoryBarrierBuffer",
        "memoryBarrierImage", "memoryBarrierShared", "groupMemoryBarrier",
        "barrier", "beginInvocationInterlockARB", "endInvocationInterlockARB",
        "beginInvocationInterlockNV", "endInvocationInterlockNV",
        "framebufferFetchBarrierEXT", "subroutine", "coherent", "volatile",
        "restrict", "shared", "xfb_buffer", "xfb_offset", "xfb_stride",
        "gl_NextBuffer", "gl_SkipComponents1", "gl_SkipComponents2",
        "gl_SkipComponents3", "gl_SkipComponents4"
    };

    private ShaderValidationPolicy() {
    }

    public static Result inspect(PreparedShaderPermutation prepared) {
        if (prepared == null) return new Result(false, "missing permutation");
        Result vertex = inspectStage("vertex", prepared.getVertex().getSource());
        if (!vertex.isSafe()) return vertex;
        if (prepared.getGeometry() != null) {
            Result geometry = inspectStage("geometry",
                prepared.getGeometry().getSource());
            if (!geometry.isSafe()) return geometry;
        }
        return inspectStage("fragment", prepared.getFragment().getSource());
    }

    private static Result inspectStage(String stage, String source) {
        if (source == null || source.indexOf('\0') >= 0) {
            return new Result(false, stage + " source is missing or contains NUL");
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (containsToken(source, token)) {
                return new Result(false, stage
                    + " source has externally visible side effect token " + token);
            }
        }
        // Interface/storage blocks can alias application buffers even when no
        // explicit atomic operation appears in the source.
        if (containsToken(source, "buffer")) {
            return new Result(false, stage + " source declares buffer storage");
        }
        // The GL 3.x API cannot enumerate every user fragment output name from
        // an already-linked program.  Until those locations are captured at
        // OptiFine's link call, explicit fragment outputs are not eligible;
        // compatibility gl_FragColor/gl_FragData remain fully supported.
        if ("fragment".equals(stage) && containsToken(source, "out")) {
            return new Result(false,
                "fragment source has an explicit output interface");
        }
        return new Result(true, "bounded side-effect-free shader text");
    }

    private static boolean containsToken(String source, String token) {
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            int end = offset + token.length();
            boolean left = offset == 0 || !identifier(source.charAt(offset - 1));
            boolean right = end == source.length()
                || !identifier(source.charAt(end));
            if (left && right) return true;
            offset = end;
        }
        return false;
    }

    private static boolean identifier(char value) {
        return value == '_' || value >= '0' && value <= '9'
            || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    public static final class Result {
        private final boolean safe;
        private final String detail;

        private Result(boolean safe, String detail) {
            this.safe = safe;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isSafe() { return safe; }
        public String getDetail() { return detail; }
    }
}
