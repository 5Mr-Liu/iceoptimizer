package dev.rlcraft.ice.optimizer.render.legacy;

/** Full-state restoration backend. Implementations must not query GL here. */
public interface GlStateDriver {
    void apply(GlStateSnapshot snapshot);
}
