package dev.rlcraft.ice.optimizer.render.frame;

/**
 * Stable semantic pass identifiers. The order mirrors Minecraft/OptiFine's
 * observable lifecycle; batching is never allowed to cross an entry.
 */
public enum RenderPass {
    ANIMATED_TEXTURE_UPLOAD(false, true),
    SHADOW_TERRAIN(true, true),
    SHADOW_ENTITY(true, true),
    SHADOW_TESR(true, true),
    SKY(false, true),
    MAIN_SOLID(true, false),
    MAIN_CUTOUT_MIPPED(true, false),
    MAIN_CUTOUT(true, false),
    ENTITY_PASS_0(false, true),
    TESR_PASS_0(false, true),
    ENTITY_PASS_1(false, true),
    TESR_PASS_1(false, true),
    ENTITY_MULTIPASS(false, true),
    ENTITY_OUTLINE(false, true),
    TRANSLUCENT(false, true),
    PARTICLES(false, true),
    LIT_PARTICLES(false, true),
    WEATHER(false, true),
    HAND(false, true),
    DEFERRED(false, true),
    COMPOSITE(false, true),
    FINAL(false, true),
    HUD_GUI(false, true),
    PORTAL_RECURSIVE(false, true);

    private final boolean conservativeOcclusionEligible;
    private final boolean hardBoundary;

    RenderPass(boolean conservativeOcclusionEligible, boolean hardBoundary) {
        this.conservativeOcclusionEligible = conservativeOcclusionEligible;
        this.hardBoundary = hardBoundary;
    }

    public boolean isConservativeOcclusionEligible() {
        return conservativeOcclusionEligible;
    }

    public boolean isHardBoundary() {
        return hardBoundary;
    }
}
