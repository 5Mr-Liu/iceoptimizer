package dev.rlcraft.ice.optimizer.render.texture;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;

/** Per-context output-certification cache for streaming and persistent PBOs. */
public final class TextureOutputValidator {
    private static long streamingContext = Long.MIN_VALUE;
    private static long persistentContext = Long.MIN_VALUE;
    private static LwjglTextureUploadSelfTest.Result streaming;
    private static LwjglTextureUploadSelfTest.Result persistent;

    private TextureOutputValidator() {
    }

    public static synchronized LwjglTextureUploadSelfTest.Result validate(
        long contextGeneration, boolean persistentPath) {
        return validate(contextGeneration, persistentPath, null);
    }

    public static synchronized LwjglTextureUploadSelfTest.Result validate(
        long contextGeneration, boolean persistentPath, CacheBudget budget) {
        if (contextGeneration <= 0L) {
            return LwjglTextureUploadSelfTest.validate(persistentPath, budget);
        }
        if (persistentPath) {
            if (persistent == null || persistentContext != contextGeneration) {
                persistent = LwjglTextureUploadSelfTest.validate(true, budget);
                persistentContext = contextGeneration;
            }
            return persistent;
        }
        if (streaming == null || streamingContext != contextGeneration) {
            streaming = LwjglTextureUploadSelfTest.validate(false, budget);
            streamingContext = contextGeneration;
        }
        return streaming;
    }

    public static synchronized void invalidate() {
        streamingContext = Long.MIN_VALUE;
        persistentContext = Long.MIN_VALUE;
        streaming = null;
        persistent = null;
    }
}
