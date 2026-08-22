package dev.rlcraft.ice.optimizer.render.optifine;

public interface ShaderSourceRepository {
    /** Returns UTF-8 text for a normalized pack-relative path, or null. */
    String load(String normalizedPath);
}
