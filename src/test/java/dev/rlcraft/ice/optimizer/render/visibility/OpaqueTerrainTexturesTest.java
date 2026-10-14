package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import org.junit.Test;

public final class OpaqueTerrainTexturesTest {
    @Test public void exactUvPositiveAndNegativeResultsAvoidRepeatedAtlasBucketSearch() throws Exception {
        OpaqueTerrainTextures textures = new OpaqueTerrainTextures(null);
        Class<?> evidenceType = Class.forName(OpaqueTerrainTextures.class.getName() + "$SpriteEvidence");
        Constructor<?> constructor = evidenceType.getDeclaredConstructor(
            float.class, float.class, float.class, float.class, boolean.class);
        constructor.setAccessible(true);
        CountingBucket bucket = new CountingBucket();
        bucket.add(constructor.newInstance(.1F,.1F,.2F,.2F,true));
        Field field = OpaqueTerrainTextures.class.getDeclaredField("cells");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") ArrayList<Object>[] cells =
            (ArrayList<Object>[]) field.get(textures);
        for (int i = 0; i < cells.length; i++) cells[i] = bucket;
        assertTrue(textures.isOpaque(.12F, .12F, .18F, .18F));
        int positiveReads = bucket.reads;
        for (int i = 0; i < 100; i++) assertTrue(textures.isOpaque(.12F, .12F, .18F, .18F));
        assertEquals(positiveReads, bucket.reads);
        assertFalse(textures.isOpaque(.4F, .4F, .5F, .5F));
        int negativeReads = bucket.reads;
        for (int i = 0; i < 100; i++) assertFalse(textures.isOpaque(.4F, .4F, .5F, .5F));
        assertEquals(negativeReads, bucket.reads);
        // Many distinct UV keys collide in the bounded cache. Collisions must
        // evict an entry, never inherit its true/false result.
        for (int i = 0; i < 5000; i++) {
            float uv = i / 5000F;
            assertEquals(uv >= .1F && uv <= .2F, textures.isOpaque(uv, uv, uv, uv));
        }
        assertTrue(textures.isOpaque(.12F, .12F, .18F, .18F));
        assertFalse(textures.isOpaque(.12F, .12F, .3F, .3F));
        assertFalse(new OpaqueTerrainTextures(null).isOpaque(.12F, .12F, .18F, .18F));
        assertFalse(textures.isOpaque(Float.NaN, 0, 1, 1));
    }

    private static final class CountingBucket extends ArrayList<Object> {
        private int reads;
        @Override public Object get(int index) { reads++; return super.get(index); }
    }
}
