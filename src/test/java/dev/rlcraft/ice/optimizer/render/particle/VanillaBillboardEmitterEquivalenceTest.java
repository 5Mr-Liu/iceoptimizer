package dev.rlcraft.ice.optimizer.render.particle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.particle.ParticleRenderAccess;
import java.nio.ByteBuffer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.Test;

/** Full synthetic-Particle equivalence belongs at build time, not client startup. */
public final class VanillaBillboardEmitterEquivalenceTest {
    @Test
    public void unrotatedAndSpriteRotatedBillboardsMatchVanillaBytes() {
        double oldX = Particle.interpPosX;
        double oldY = Particle.interpPosY;
        double oldZ = Particle.interpPosZ;
        Vec3d oldView = Particle.cameraViewDir;
        try {
            Particle.interpPosX = 4.125D;
            Particle.interpPosY = -2.5D;
            Particle.interpPosZ = 9.75D;
            Particle.cameraViewDir = new Vec3d(0.31D, -0.44D, 0.842D)
                .normalize();
            ValidationParticle particle = new ValidationParticle();
            assertEquivalent(particle, 0.375F, 0.77F, -0.23F,
                0.41F, -0.66F, 0.52F);
            particle.useRotatedSprite(1.125F, -0.625F,
                new ValidationSprite());
            assertEquivalent(particle, 0.625F, -0.13F, 0.91F,
                -0.72F, 0.37F, 0.19F);
        } finally {
            Particle.interpPosX = oldX;
            Particle.interpPosY = oldY;
            Particle.interpPosZ = oldZ;
            Particle.cameraViewDir = oldView;
        }
    }

    private static void assertEquivalent(ValidationParticle particle,
                                         float partialTicks,
                                         float rotationX, float rotationZ,
                                         float rotationYZ, float rotationXY,
                                         float rotationXZ) {
        BufferBuilder legacy = new BufferBuilder(256);
        BufferBuilder modern = new BufferBuilder(256);
        legacy.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        modern.begin(7, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        particle.renderParticle(legacy, null, partialTicks, rotationX, rotationZ,
            rotationYZ, rotationXY, rotationXZ);
        ParticleInstanceStream stream = new ParticleInstanceStream(64);
        ParticleState state = new ParticleState(0, 1, 0, 770, 771,
            false, false, 1L);
        assertTrue(VanillaBillboardEmitter.emit(particle, particle, modern,
            (Entity) null, partialTicks, rotationX, rotationZ, rotationYZ,
            rotationXY, rotationXZ, stream, state, 0L, new double[12]));
        assertEquals(legacy.getVertexCount(), modern.getVertexCount());
        int bytes = legacy.getVertexCount() * legacy.getVertexFormat().getSize();
        ByteBuffer left = legacy.getByteBuffer();
        ByteBuffer right = modern.getByteBuffer();
        for (int index = 0; index < bytes; index++) {
            assertEquals("byte " + index, left.get(index), right.get(index));
        }
    }

    private static final class ValidationParticle extends Particle
        implements ParticleRenderAccess {
        private ValidationParticle() {
            super((World) null, 6.25D, -0.5D, 14.0D);
            prevPosX = 5.5D;
            prevPosY = -1.75D;
            prevPosZ = 12.25D;
            posX = 7.875D;
            posY = 0.625D;
            posZ = 15.5D;
            particleTextureIndexX = 7;
            particleTextureIndexY = 11;
            particleScale = 1.375F;
            particleRed = 0.23F;
            particleGreen = 0.61F;
            particleBlue = 0.87F;
            particleAlpha = 0.42F;
        }

        @Override public int getBrightnessForRender(float partialTick) {
            return 0x00D00070;
        }

        private void useRotatedSprite(float angle, float previous,
                                      TextureAtlasSprite sprite) {
            particleAngle = angle;
            prevParticleAngle = previous;
            particleTexture = sprite;
        }

        @Override public double ice$previousX() { return prevPosX; }
        @Override public double ice$previousY() { return prevPosY; }
        @Override public double ice$previousZ() { return prevPosZ; }
        @Override public double ice$currentX() { return posX; }
        @Override public double ice$currentY() { return posY; }
        @Override public double ice$currentZ() { return posZ; }
        @Override public int ice$textureIndexX() { return particleTextureIndexX; }
        @Override public int ice$textureIndexY() { return particleTextureIndexY; }
        @Override public float ice$particleScale() { return particleScale; }
        @Override public float ice$particleRed() { return particleRed; }
        @Override public float ice$particleGreen() { return particleGreen; }
        @Override public float ice$particleBlue() { return particleBlue; }
        @Override public float ice$particleAlpha() { return particleAlpha; }
        @Override public TextureAtlasSprite ice$particleTexture() {
            return particleTexture;
        }
        @Override public float ice$particleAngle() { return particleAngle; }
        @Override public float ice$previousParticleAngle() {
            return prevParticleAngle;
        }
    }

    private static final class ValidationSprite extends TextureAtlasSprite {
        private ValidationSprite() { super("ice-validation"); }
        @Override public float getMinU() { return 0.1171875F; }
        @Override public float getMaxU() { return 0.4921875F; }
        @Override public float getMinV() { return 0.203125F; }
        @Override public float getMaxV() { return 0.828125F; }
    }
}
