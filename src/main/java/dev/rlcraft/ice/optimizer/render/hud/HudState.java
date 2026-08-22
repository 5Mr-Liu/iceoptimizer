package dev.rlcraft.ice.optimizer.render.hud;

/** Every Forge event/FBO/scissor/stencil change produces a distinct key. */
public final class HudState {
    private final int framebuffer;
    private final int program;
    private final int texture;
    private final int fontPage;
    private final int blendSource;
    private final int blendDestination;
    private final int scissorHash;
    private final int stencilHash;
    private final long eventScope;
    private final boolean fontShadow;

    public HudState(int framebuffer, int program, int texture, int fontPage,
                    int blendSource, int blendDestination, int scissorHash,
                    int stencilHash, long eventScope, boolean fontShadow) {
        if (framebuffer < 0 || program < 0 || texture < 0 || fontPage < 0
            || eventScope < 0L) throw new IllegalArgumentException("HUD state");
        this.framebuffer = framebuffer;
        this.program = program;
        this.texture = texture;
        this.fontPage = fontPage;
        this.blendSource = blendSource;
        this.blendDestination = blendDestination;
        this.scissorHash = scissorHash;
        this.stencilHash = stencilHash;
        this.eventScope = eventScope;
        this.fontShadow = fontShadow;
    }

    public long getEventScope() { return eventScope; }
    public boolean isFontShadow() { return fontShadow; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HudState)) return false;
        HudState other = (HudState) value;
        return framebuffer == other.framebuffer && program == other.program
            && texture == other.texture && fontPage == other.fontPage
            && blendSource == other.blendSource
            && blendDestination == other.blendDestination
            && scissorHash == other.scissorHash && stencilHash == other.stencilHash
            && eventScope == other.eventScope && fontShadow == other.fontShadow;
    }

    @Override public int hashCode() {
        int result = framebuffer;
        result = 31 * result + program;
        result = 31 * result + texture;
        result = 31 * result + fontPage;
        result = 31 * result + blendSource;
        result = 31 * result + blendDestination;
        result = 31 * result + scissorHash;
        result = 31 * result + stencilHash;
        result = 31 * result + (int) (eventScope ^ (eventScope >>> 32));
        return 31 * result + (fontShadow ? 1 : 0);
    }
}
