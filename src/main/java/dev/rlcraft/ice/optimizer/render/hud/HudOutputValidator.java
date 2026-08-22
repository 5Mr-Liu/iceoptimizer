package dev.rlcraft.ice.optimizer.render.hud;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;

/** One exact offscreen HUD comparison per GL-context generation. */
public final class HudOutputValidator {
    private static long generation;
    private static LwjglHudOutputSelfTest.Result cached;

    private HudOutputValidator() {
    }

    public static synchronized LwjglHudOutputSelfTest.Result validate(
        long contextGeneration, CacheBudget budget) {
        if (contextGeneration <= 0L) {
            throw new IllegalArgumentException("HUD validation generation");
        }
        if (cached == null || generation != contextGeneration) {
            cached = LwjglHudOutputSelfTest.execute(budget);
            generation = contextGeneration;
        }
        return cached;
    }

    public static synchronized void invalidate() {
        generation = 0L;
        cached = null;
    }
}
