package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;

/** Upload-time source coordinates shared by the native arena and software coverage. */
public final class TerrainVertexSpace {
    private static final float MODEL_SCALE = 1.000001F;
    private static final float MODEL_TRANSLATION = 8.0F * MODEL_SCALE - 8.0F;
    private static final Field X = field(BufferBuilder.class, "xOffset", "field_179004_l");
    private static final Field Y = field(BufferBuilder.class, "yOffset", "field_179005_m");
    private static final Field Z = field(BufferBuilder.class, "zOffset", "field_179002_n");
    private static final Field REGION_X = field(RenderChunk.class, "regionX");
    private static final Field REGION_Z = field(RenderChunk.class, "regionZ");
    private static final Method REGIONS = regionsMethod();
    private static boolean failedRegionsLookup;

    public final int originX, originY, originZ;
    public final float scale, translation;
    public final boolean renderRegions;

    private TerrainVertexSpace(int x, int y, int z, boolean regions) {
        originX = x; originY = y; originZ = z;
        renderRegions = regions;
        scale = regions ? 1.0F : MODEL_SCALE;
        translation = regions ? 0.0F : MODEL_TRANSLATION;
    }

    /** -1 is unavailable, 0 is the ordinary chunk transform, 1 is OptiFine Render Regions. */
    public static int currentMode() {
        if (failedRegionsLookup) return -1;
        if (REGIONS == null) return 0;
        try { return Boolean.TRUE.equals(REGIONS.invoke(null)) ? 1 : 0; }
        catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); return -1; }
    }

    public static TerrainVertexSpace capture(BufferBuilder builder, RenderChunk chunk) {
        if (builder == null || chunk == null || X == null || Y == null || Z == null) return null;
        int mode = currentMode();
        if (mode < 0) return null;
        try {
            BlockPos position = chunk.getPosition();
            int x = position.getX(), y = position.getY(), z = position.getZ();
            if (mode == 1) {
                if (REGION_X == null || REGION_Z == null) return null;
                x = REGION_X.getInt(chunk);
                z = REGION_Z.getInt(chunk);
                y = y >> 8 << 8;
            }
            if (X.getDouble(builder) != -(double) x || Y.getDouble(builder) != -(double) y
                || Z.getDouble(builder) != -(double) z) return null;
            // Reviewed OptiFine G5 drawRegion applies (regionX, 0, regionZ),
            // without RenderChunk.multModelviewMatrix. Ordinary draws apply
            // the slightly expanded chunk model matrix around their origin.
            return new TerrainVertexSpace(x, mode == 1 ? 0 : y, z, mode == 1);
        } catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); return null; }
    }

    private static Field field(Class<?> type, String... names) {
        for (String name : names) {
            try {
                Field value = type.getDeclaredField(name);
                value.setAccessible(true);
                return value;
            } catch (Throwable failure) { FatalErrors.rethrowIfFatal(failure); }
        }
        return null;
    }

    private static Method regionsMethod() {
        try {
            return Class.forName("Config", false, TerrainVertexSpace.class.getClassLoader())
                .getMethod("isRenderRegions");
        } catch (ClassNotFoundException vanilla) {
            return null;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failedRegionsLookup = true;
            return null;
        }
    }
}
