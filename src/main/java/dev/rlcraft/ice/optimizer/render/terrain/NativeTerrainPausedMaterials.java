package dev.rlcraft.ice.optimizer.render.terrain;

import java.util.IdentityHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.lwjgl.opengl.GL11;

/** F6 uses ordinary per-chunk VBOs but still needs OptiFine's material semantics. */
public final class NativeTerrainPausedMaterials {
    private static final IdentityHashMap<VertexBuffer,NativeTerrainMaterials> MESHES=new IdentityHashMap<VertexBuffer,NativeTerrainMaterials>();
    private NativeTerrainPausedMaterials() { }
    public static NativeTerrainMaterials capture(BufferBuilder builder) {
        TerrainUploadContext.Value context=TerrainUploadContext.take();
        if(context==null)return null;
        NativeTerrainMaterials materials=NativeTerrainMaterials.capture(builder,builder.getVertexCount(),context.getLayer());
        if(materials!=null)materials.resolve();
        return materials;
    }
    public static void publish(VertexBuffer buffer,NativeTerrainMaterials materials){if(materials!=null)MESHES.put(buffer,materials);else MESHES.remove(buffer);}
    static void release(VertexBuffer buffer){MESHES.remove(buffer);}
    static void clear(){MESHES.clear();}
    public static void draw(VertexBuffer buffer,int mode) {
        NativeTerrainMaterials materials=MESHES.get(buffer);
        if(materials==null){buffer.drawArrays(mode);return;}
        NativeTerrainTextureState state=new NativeTerrainTextureState();
        try {for(int i=0;i<materials.first.length;i++){state.bind(materials.texture(i));GL11.glDrawArrays(mode,materials.first[i],materials.count[i]);}}
        finally {state.close();}
    }
}
