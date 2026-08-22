package dev.rlcraft.ice.optimizer.render.optifine;

/** Exact program/drawbuffer/FBO/alpha/blend/render-scale comparison. */
public final class ShaderProgramStateValidator {
    public ShaderStateValidationResult compare(OptifineProgramState expected,
                                               OptifineProgramState actual) {
        if (expected == null || actual == null) {
            return new ShaderStateValidationResult(false, "missing program state");
        }
        if (!expected.getName().equals(actual.getName())) return mismatch("program name");
        if (!expected.getStage().equals(actual.getStage())) return mismatch("program stage");
        // Native and OptiFine programs necessarily have different GL names;
        // the logical name/stage and all observable state are the identity.
        ShaderFramebufferState expectedFramebuffer = expected.getFramebufferState();
        ShaderFramebufferState actualFramebuffer = actual.getFramebufferState();
        if (expectedFramebuffer == null || actualFramebuffer == null) {
            return mismatch("FBO attachment layout unavailable");
        }
        if (!expectedFramebuffer.equals(actualFramebuffer)) {
            return mismatch("FBO attachment layout");
        }
        if (!java.util.Arrays.equals(expected.getDrawBuffers(), actual.getDrawBuffers())) {
            return mismatch("draw buffers");
        }
        if (expected.getCompositeMipmap() != actual.getCompositeMipmap()) {
            return mismatch("composite mipmap");
        }
        if (expected.getInstanceCount() != actual.getInstanceCount()) {
            return mismatch("instance count");
        }
        if (!equal(expected.getAlpha(), actual.getAlpha())) return mismatch("alpha state");
        if (!equal(expected.getBlend(), actual.getBlend())) return mismatch("blend state");
        if (!equal(expected.getRenderScale(), actual.getRenderScale())) {
            return mismatch("render scale");
        }
        return new ShaderStateValidationResult(true, "equivalent");
    }

    private static ShaderStateValidationResult mismatch(String field) {
        return new ShaderStateValidationResult(false, field + " differs");
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
