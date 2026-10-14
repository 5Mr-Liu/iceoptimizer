package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;

/** Immutable atlas evidence with compiler-thread-local UV lookup scratch. Only immutable, non-animated, fully opaque sprites qualify. */
final class OpaqueTerrainTextures implements TerrainOccluderMesh.Opacity {
    private static final int GRID = 32;
    private static final int MAX_PIXEL_CHECKS = 1024 * 1024;
    private static final int MAX_BUCKET_ENTRIES = 128 * 1024;
    private static final int UV_CACHE_SIZE = 1024;
    private final ArrayList<SpriteEvidence>[] cells;
    private final ThreadLocal<UvCache> uvCache = new ThreadLocal<UvCache>() {
        @Override protected UvCache initialValue() { return new UvCache(); }
    };

    @SuppressWarnings("unchecked")
    OpaqueTerrainTextures(TextureMap atlas) {
        cells = (ArrayList<SpriteEvidence>[]) new ArrayList<?>[GRID * GRID];
        Map<?, ?> sprites = uploadedSprites(atlas);
        if (sprites == null) return;
        int remaining = MAX_BUCKET_ENTRIES;
        for (Object value : sprites.values()) {
            if (!(value instanceof TextureAtlasSprite)) continue;
            TextureAtlasSprite sprite = (TextureAtlasSprite) value;
            String type = sprite.getClass().getName();
            if (sprite.getClass() != TextureAtlasSprite.class
                && !"pl.asie.foamfix.client.FastTextureAtlasSprite".equals(type)) continue;
            if (sprite.hasAnimationMetadata() || sprite.getFrameCount() != 1) continue;
            float u0 = sprite.getMinU(), v0 = sprite.getMinV();
            float u1 = sprite.getMaxU(), v1 = sprite.getMaxV();
            if (!valid(u0, v0, u1, v1)) continue;
            SpriteEvidence evidence = new SpriteEvidence(u0, v0, u1, v1,
                fullyOpaque(sprite.getFrameTextureData(0)));
            for (int y = cell(v0); y <= cell(v1); y++) {
                for (int x = cell(u0); x <= cell(u1); x++) {
                    if (remaining-- <= 0) return;
                    int index = y * GRID + x;
                    if (cells[index] == null) cells[index] = new ArrayList<SpriteEvidence>();
                    cells[index].add(evidence);
                }
            }
        }
    }

    boolean isOpaque(float minU, float minV, float maxU, float maxV) {
        if (!valid(minU, minV, maxU, maxV)) return false;
        UvCache cache = uvCache.get();
        int[] uvKeys = cache.keys;
        byte[] uvResults = cache.results;
        int u0 = Float.floatToIntBits(minU), v0 = Float.floatToIntBits(minV);
        int u1 = Float.floatToIntBits(maxU), v1 = Float.floatToIntBits(maxV);
        int hash = ((u0 * 31 + v0) * 31 + u1) * 31 + v1;
        int slot = (hash ^ hash >>> 16) & (UV_CACHE_SIZE - 1);
        int key = slot * 4;
        if (uvResults[slot] != 0 && uvKeys[key] == u0 && uvKeys[key + 1] == v0
            && uvKeys[key + 2] == u1 && uvKeys[key + 3] == v1) {
            return uvResults[slot] == 1;
        }
        boolean result = findOpaque(minU, minV, maxU, maxV);
        uvKeys[key] = u0; uvKeys[key + 1] = v0;
        uvKeys[key + 2] = u1; uvKeys[key + 3] = v1;
        uvResults[slot] = (byte) (result ? 1 : 2);
        return result;
    }

    @Override public boolean opaque(float minU, float minV, float maxU, float maxV) {
        return isOpaque(minU, minV, maxU, maxV);
    }

    private boolean findOpaque(float minU, float minV, float maxU, float maxV) {
        ArrayList<SpriteEvidence> bucket = cells[cell(minV) * GRID + cell(minU)];
        if (bucket == null) return false;
        for (int i = 0; i < bucket.size(); i++) {
            SpriteEvidence sprite = bucket.get(i);
            if (minU < sprite.minU || minV < sprite.minV
                || maxU > sprite.maxU || maxV > sprite.maxV) continue;
            return sprite.opaque;
        }
        return false;
    }

    static boolean fullyOpaque(int[][] mipmaps) {
        if (mipmaps == null || mipmaps.length == 0) return false;
        int remaining = MAX_PIXEL_CHECKS;
        for (int[] level : mipmaps) {
            if (level == null || level.length == 0 || level.length > remaining) return false;
            remaining -= level.length;
            for (int pixel : level) if ((pixel >>> 24) != 255) return false;
        }
        return true;
    }

    private static final class SpriteEvidence {
        final float minU, minV, maxU, maxV;
        final boolean opaque;
        SpriteEvidence(float minU, float minV, float maxU, float maxV, boolean opaque) {
            this.minU = minU; this.minV = minV; this.maxU = maxU; this.maxV = maxV;
            this.opaque = opaque;
        }
    }

    private static final class UvCache {
        final int[] keys = new int[UV_CACHE_SIZE * 4];
        final byte[] results = new byte[UV_CACHE_SIZE];
    }

    private static boolean valid(float u0, float v0, float u1, float v1) {
        return Float.isFinite(u0) && Float.isFinite(v0) && Float.isFinite(u1)
            && Float.isFinite(v1) && u0 >= 0 && v0 >= 0 && u1 <= 1 && v1 <= 1
            && u0 <= u1 && v0 <= v1;
    }

    private static int cell(float coordinate) {
        return Math.min(GRID - 1, (int) (coordinate * GRID));
    }

    private static Map<?, ?> uploadedSprites(TextureMap atlas) {
        if (atlas == null) return null;
        for (String name : new String[] { "mapUploadedSprites", "field_94252_e" }) {
            try {
                Field field = TextureMap.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(atlas);
                if (value instanceof Map) return (Map<?, ?>) value;
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
            }
        }
        return null;
    }
}
