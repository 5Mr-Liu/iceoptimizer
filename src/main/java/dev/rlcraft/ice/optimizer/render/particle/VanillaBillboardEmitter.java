package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.compat.particle.ParticleRenderAccess;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Allocation-free equivalent of the vanilla Particle billboard emitter. */
public final class VanillaBillboardEmitter {
    private VanillaBillboardEmitter() {
    }

    /**
     * Emits four vertices in exactly the same arithmetic and fluent-call order
     * as Particle.renderParticle.  The caller supplies per-scope scratch space
     * so no object or array is allocated for an individual particle.
     */
    public static boolean emit(Particle particle, ParticleRenderAccess access,
                               BufferBuilder buffer, Entity camera,
                               float partialTicks, float rotationX,
                               float rotationZ, float rotationYZ,
                               float rotationXY, float rotationXZ,
                               ParticleInstanceStream stream,
                               ParticleState state, long sequence,
                               double[] corners) {
        return emit0(particle, access, buffer, null, camera, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ,
            stream, state, sequence, corners);
    }

    public static boolean emitToRenderer(Particle particle,
                               ParticleRenderAccess access,
                               LwjglParticleRenderer renderer, Entity camera,
                               float partialTicks, float rotationX,
                               float rotationZ, float rotationYZ,
                               float rotationXY, float rotationXZ,
                               ParticleInstanceStream stream,
                               ParticleState state, long sequence,
                               double[] corners) {
        return emit0(particle, access, null, renderer, camera, partialTicks,
            rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ,
            stream, state, sequence, corners);
    }

    private static boolean emit0(Particle particle, ParticleRenderAccess access,
                               BufferBuilder buffer,
                               LwjglParticleRenderer renderer, Entity camera,
                               float partialTicks, float rotationX,
                               float rotationZ, float rotationYZ,
                               float rotationXY, float rotationXZ,
                               ParticleInstanceStream stream,
                               ParticleState state, long sequence,
                               double[] corners) {
        if (particle == null || access == null
            || (buffer == null && renderer == null) || stream == null
            || state == null || corners == null || corners.length < 12
            || !stream.canRecord(sequence)) return false;

        float minU = (float) access.ice$textureIndexX() / 16.0F;
        float maxU = minU + 0.0624375F;
        float minV = (float) access.ice$textureIndexY() / 16.0F;
        float maxV = minV + 0.0624375F;
        float scale = 0.1F * access.ice$particleScale();
        TextureAtlasSprite texture = access.ice$particleTexture();
        if (texture != null) {
            minU = texture.getMinU();
            maxU = texture.getMaxU();
            minV = texture.getMinV();
            maxV = texture.getMaxV();
        }

        float x = (float) (access.ice$previousX()
            + (access.ice$currentX() - access.ice$previousX()) * (double) partialTicks
            - Particle.interpPosX);
        float y = (float) (access.ice$previousY()
            + (access.ice$currentY() - access.ice$previousY()) * (double) partialTicks
            - Particle.interpPosY);
        float z = (float) (access.ice$previousZ()
            + (access.ice$currentZ() - access.ice$previousZ()) * (double) partialTicks
            - Particle.interpPosZ);
        int packedLight = particle.getBrightnessForRender(partialTicks);
        int lightU = packedLight >> 16 & 65535;
        int lightV = packedLight & 65535;

        corners[0] = (double) (-rotationX * scale - rotationXY * scale);
        corners[1] = (double) (-rotationZ * scale);
        corners[2] = (double) (-rotationYZ * scale - rotationXZ * scale);
        corners[3] = (double) (-rotationX * scale + rotationXY * scale);
        corners[4] = (double) (rotationZ * scale);
        corners[5] = (double) (-rotationYZ * scale + rotationXZ * scale);
        corners[6] = (double) (rotationX * scale + rotationXY * scale);
        corners[7] = (double) (rotationZ * scale);
        corners[8] = (double) (rotationYZ * scale + rotationXZ * scale);
        corners[9] = (double) (rotationX * scale - rotationXY * scale);
        corners[10] = (double) (-rotationZ * scale);
        corners[11] = (double) (rotationYZ * scale - rotationXZ * scale);

        float angle = access.ice$particleAngle();
        float previousAngle = access.ice$previousParticleAngle();
        float renderedAngle = angle;
        if (angle != 0.0F) {
            renderedAngle = angle + (angle - previousAngle) * partialTicks;
            float cosine = MathHelper.cos(renderedAngle * 0.5F);
            float sine = MathHelper.sin(renderedAngle * 0.5F);
            Vec3d view = Particle.cameraViewDir;
            if (view == null) throw new NullPointerException("Particle.cameraViewDir");
            double axisX = (double) (sine * (float) view.x);
            double axisY = (double) (sine * (float) view.y);
            double axisZ = (double) (sine * (float) view.z);
            for (int offset = 0; offset < 12; offset += 3) {
                rotate(corners, offset, axisX, axisY, axisZ, cosine);
            }
        }

        float red = access.ice$particleRed();
        float green = access.ice$particleGreen();
        float blue = access.ice$particleBlue();
        float alpha = access.ice$particleAlpha();
        if (renderer != null) {
            if (!renderer.recordQuad(x, y, z, corners, minU, minV, maxU, maxV,
                red, green, blue, alpha, lightU, lightV)) return false;
            if (!stream.record(state, x, y, z, scale, renderedAngle,
                packColor(red, green, blue, alpha), packedLight,
                minU, minV, maxU, maxV, sequence)) {
                renderer.rollbackLastQuad();
                throw new IllegalStateException("particle stream reservation changed");
            }
            return true;
        }
        if (!stream.record(state, x, y, z, scale, renderedAngle,
            packColor(red, green, blue, alpha), packedLight, minU, minV,
            maxU, maxV, sequence)) {
            throw new IllegalStateException("particle stream reservation changed");
        }

        buffer.pos((double) x + corners[0], (double) y + corners[1],
            (double) z + corners[2]).tex((double) maxU, (double) maxV)
            .color(red, green, blue, alpha).lightmap(lightU, lightV).endVertex();
        buffer.pos((double) x + corners[3], (double) y + corners[4],
            (double) z + corners[5]).tex((double) maxU, (double) minV)
            .color(red, green, blue, alpha).lightmap(lightU, lightV).endVertex();
        buffer.pos((double) x + corners[6], (double) y + corners[7],
            (double) z + corners[8]).tex((double) minU, (double) minV)
            .color(red, green, blue, alpha).lightmap(lightU, lightV).endVertex();
        buffer.pos((double) x + corners[9], (double) y + corners[10],
            (double) z + corners[11]).tex((double) minU, (double) maxV)
            .color(red, green, blue, alpha).lightmap(lightU, lightV).endVertex();
        return true;
    }

    private static void rotate(double[] corners, int offset, double axisX,
                               double axisY, double axisZ, float cosine) {
        double x = corners[offset];
        double y = corners[offset + 1];
        double z = corners[offset + 2];
        double dotCornerAxis = x * axisX + y * axisY + z * axisZ;
        double firstScale = 2.0D * dotCornerAxis;
        double firstX = axisX * firstScale;
        double firstY = axisY * firstScale;
        double firstZ = axisZ * firstScale;
        double axisDot = axisX * axisX + axisY * axisY + axisZ * axisZ;
        double secondScale = (double) (cosine * cosine) - axisDot;
        double combinedX = firstX + x * secondScale;
        double combinedY = firstY + y * secondScale;
        double combinedZ = firstZ + z * secondScale;
        double crossX = axisY * z - axisZ * y;
        double crossY = axisZ * x - axisX * z;
        double crossZ = axisX * y - axisY * x;
        double thirdScale = (double) (2.0F * cosine);
        corners[offset] = combinedX + crossX * thirdScale;
        corners[offset + 1] = combinedY + crossY * thirdScale;
        corners[offset + 2] = combinedZ + crossZ * thirdScale;
    }

    private static int packColor(float red, float green, float blue, float alpha) {
        int r = (int) (red * 255.0F) & 255;
        int g = (int) (green * 255.0F) & 255;
        int b = (int) (blue * 255.0F) & 255;
        int a = (int) (alpha * 255.0F) & 255;
        return r << 24 | g << 16 | b << 8 | a;
    }
}
