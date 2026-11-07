package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;

/** Immutable quad ranges captured before the pooled builder can be reused. */
public final class NativeTerrainMaterials {
    private static final Field QUADS = field(BufferBuilder.class, "quadSprites");
    private static final Field TEXTURE = field(TextureAtlasSprite.class, "glSpriteTextureId");
    private static final Field OVERLAY = overlayField();
    final int[] first, count;
    private final Object[] sprites;
    private int[] textures;

    private NativeTerrainMaterials(int[] first, int[] count, Object[] sprites) {
        this.first=first; this.count=count; this.sprites=sprites;
    }

    public static boolean rejectUnsupportedMultiTexture(boolean enabled) {
        if (enabled && (QUADS==null || TEXTURE==null || OVERLAY==null))
            throw new IllegalStateException("ICE: unsupported OptiFine multi-texture layout; expected G5 quadSprites/sprite textures");
        return false; // ICE's terrain path now preserves and draws sprite ranges.
    }

    static NativeTerrainMaterials capture(BufferBuilder builder, int vertices, BlockRenderLayer layer) {
        if (QUADS==null) return null;
        try {
            Object[] quads=(Object[])QUADS.get(builder);
            if(quads==null) return null;
            if(vertices/4>quads.length) throw new IllegalStateException("Incomplete terrain sprite array");
            return ranges(quads, vertices/4, layer==BlockRenderLayer.TRANSLUCENT, OVERLAY.get(null));
        } catch(IllegalAccessException e) { throw new IllegalStateException("Cannot capture terrain materials",e); }
    }

    static NativeTerrainMaterials ranges(Object[] quads, int size, boolean transparent, Object overlay) {
        List<Run> runs=new ArrayList<Run>();
        Map<Object,List<Run>> grouped=transparent?null:new LinkedHashMap<Object,List<Run>>();
        List<Run> overlays=new ArrayList<Run>();
        for(int first=0;first<size;) {
            Object sprite=quads[first]; int end=first+1;
            while(end<size && quads[end]==sprite)end++;
            if(sprite!=null) {
                Run run=new Run(first*4,(end-first)*4,sprite);
                if(sprite==overlay)overlays.add(run);
                else if(transparent)runs.add(run);
                else {
                    List<Run> list=grouped.get(sprite);
                    if(list==null)grouped.put(sprite,list=new ArrayList<Run>());
                    list.add(run);
                }
            }
            first=end;
        }
        if(!transparent)for(List<Run> list:grouped.values())runs.addAll(list);
        // G5 defers grass-side overlay; transparent drawForIcon emits only its first run.
        if(transparent) {if(!overlays.isEmpty())runs.add(overlays.get(0));}
        else runs.addAll(overlays);
        int[] first=new int[runs.size()],count=new int[runs.size()];Object[] sprites=new Object[runs.size()];
        for(int i=0;i<runs.size();i++){Run r=runs.get(i);first[i]=r.first;count[i]=r.count;sprites[i]=r.sprite;}
        return new NativeTerrainMaterials(first,count,sprites);
    }

    void resolve() {
        if(textures!=null)return;
        int[] resolved=new int[sprites.length];
        try {
            for(int i=0;i<resolved.length;i++) {
                resolved[i]=TEXTURE.getInt(sprites[i]);
                if(resolved[i]<0)throw new IllegalStateException("ICE terrain sprite texture is not uploaded");
            }
        } catch(IllegalAccessException e){throw new IllegalStateException(e);}
        textures=resolved;
    }
    int texture(int run){return textures[run];}
    private static Field field(Class<?> type,String name){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException e){return null;}}
    private static Field overlayField(){try{return field(Class.forName("net.optifine.util.TextureUtils",false,NativeTerrainMaterials.class.getClassLoader()),"iconGrassSideOverlay");}catch(ClassNotFoundException e){return null;}}
    private static final class Run {
        final int first,count;final Object sprite;
        Run(int first,int count,Object sprite){this.first=first;this.count=count;this.sprite=sprite;}
    }
}
