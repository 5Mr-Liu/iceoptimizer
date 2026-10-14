package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.junit.Test;
import sun.misc.Unsafe;

public final class OpaqueTerrainTexturesSnapshotTest {
    @Test public void atlasEvidenceDoesNotFollowMutableSpritePixelsOrUvs() throws Exception {
        TextureMap atlas = atlas();
        OpaqueTerrainTextures snapshot = new OpaqueTerrainTextures(atlas);
        TextureAtlasSprite sprite = sprites(atlas).values().iterator().next();
        sprite.getFrameTextureData(0)[0][0] = 0;
        field(TextureAtlasSprite.class, "minU").setFloat(sprite, .5F);
        sprites(atlas).clear();
        assertTrue(snapshot.opaque(.1F,.1F,.9F,.9F));
        assertFalse(new OpaqueTerrainTextures(atlas).opaque(.1F,.1F,.9F,.9F));
    }

    @Test public void compilerThreadsHaveIndependentCachesOverOneImmutableSnapshot() throws Exception {
        final OpaqueTerrainTextures snapshot = new OpaqueTerrainTextures(atlas());
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread[] workers = new Thread[4];
        for (int n = 0; n < workers.length; n++) {
            workers[n] = new Thread(() -> {
                try {
                    for (int i = 0; i < 10000; i++) {
                        float min = (i & 63) / 128F;
                        assertTrue(snapshot.opaque(min,min,1-min,1-min));
                        assertFalse(snapshot.opaque(-.1F,min,.9F,.9F));
                    }
                } catch (Throwable error) { failure.compareAndSet(null,error); }
            });
            workers[n].start();
        }
        for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    static TextureMap atlas() throws Exception {
        Field singleton=field(Unsafe.class,"theUnsafe");
        TextureMap atlas=(TextureMap)((Unsafe)singleton.get(null)).allocateInstance(TextureMap.class);
        Constructor<TextureAtlasSprite> constructor=TextureAtlasSprite.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        TextureAtlasSprite sprite=constructor.newInstance("ice:opaque_test");
        sprite.setIconWidth(1); sprite.setIconHeight(1);
        sprite.setFramesTextureData(Collections.singletonList(new int[][] {new int[]{-1}}));
        field(TextureAtlasSprite.class,"minU").setFloat(sprite,0);
        field(TextureAtlasSprite.class,"minV").setFloat(sprite,0);
        field(TextureAtlasSprite.class,"maxU").setFloat(sprite,1);
        field(TextureAtlasSprite.class,"maxV").setFloat(sprite,1);
        Map<String,TextureAtlasSprite> values=new LinkedHashMap<String,TextureAtlasSprite>();
        values.put("test",sprite);field(TextureMap.class,"mapUploadedSprites").set(atlas,values);
        return atlas;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,TextureAtlasSprite> sprites(TextureMap atlas) throws Exception {
        return (Map<String,TextureAtlasSprite>)field(TextureMap.class,"mapUploadedSprites").get(atlas);
    }
    private static Field field(Class<?> owner,String name)throws Exception {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;
    }
}
