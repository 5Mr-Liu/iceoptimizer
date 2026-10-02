package dev.rlcraft.ice.optimizer.compat.particle;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Read-only ABI injected into the vanilla Particle base class.  The accessors
 * expose only fields consumed by Particle.renderParticle's base billboard
 * implementation; simulation and mutable state remain owned by Minecraft.
 */
public interface ParticleRenderAccess {
    double ice$previousX();
    double ice$previousY();
    double ice$previousZ();
    double ice$currentX();
    double ice$currentY();
    double ice$currentZ();
    int ice$textureIndexX();
    int ice$textureIndexY();
    float ice$particleScale();
    float ice$particleRed();
    float ice$particleGreen();
    float ice$particleBlue();
    float ice$particleAlpha();
    TextureAtlasSprite ice$particleTexture();
    float ice$particleAngle();
    float ice$previousParticleAngle();
}
