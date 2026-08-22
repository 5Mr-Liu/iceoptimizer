package dev.rlcraft.ice.optimizer.compat.hud;

/**
 * Narrow state ABI injected into vanilla FontRenderer for a certified cached
 * string replay.  It exposes no mutable arrays and is used only on the render
 * thread while the enclosing HUD scope owns the draw.
 */
public interface FontRenderCacheAccess {
    boolean ice$fontStylesClear();
    float ice$fontPosX();
    void ice$beginCachedFont(float x, float y, float red, float green,
                             float blue, float alpha);
    void ice$finishCachedFont(float x);
}
