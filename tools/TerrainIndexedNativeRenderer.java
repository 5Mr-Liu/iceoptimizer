package dev.rlcraft.ice.benchmark;

import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Captured-scene lossless indexed quads, fixed pipeline; offline diagnostic only. */
public final class TerrainIndexedNativeRenderer implements AutoCloseable {
    private static final class Mesh {
        TerrainVertexDedupProbe.Indexed data;
        int buffer,base,type;long indexOffset;
    }
    private final Map<TerrainSceneData.Section,Mesh> meshes=new IdentityHashMap<TerrainSceneData.Section,Mesh>();
    private final Map<Integer,Integer> buffers=new HashMap<Integer,Integer>();
    private int elements,bound;
    public final long vertexBytes,indexBytes;
    public TerrainIndexedNativeRenderer(TerrainSceneData scene) {
        long vertexTotal=0,indexTotal=0;
        Map<Integer,Integer> sizes=new HashMap<Integer,Integer>();
        for(TerrainSceneData.Section s:scene.sections)if(s.owned) {
            Mesh mesh=new Mesh();mesh.data=TerrainVertexDedupProbe.encode(s.data);
            int offset=sizes.containsKey(s.buffer)?sizes.get(s.buffer):0;
            mesh.base=offset/28;mesh.indexOffset=indexTotal;
            mesh.type=mesh.data.indexBytes==2?GL11.GL_UNSIGNED_SHORT:GL11.GL_UNSIGNED_INT;
            sizes.put(s.buffer,offset+mesh.data.vertices.length);
            vertexTotal+=mesh.data.vertices.length;
            indexTotal+=(long)mesh.data.indices.length*mesh.data.indexBytes;
            meshes.put(s,mesh);
        }
        vertexBytes=vertexTotal;indexBytes=indexTotal;
        try {
            for(Map.Entry<Integer,Integer> entry:sizes.entrySet()) {
                int id=GL15.glGenBuffers();buffers.put(entry.getKey(),id);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,entry.getValue(),GL15.GL_STATIC_DRAW);
            }
            elements=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,elements);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indexTotal,GL15.GL_STATIC_DRAW);
            for(TerrainSceneData.Section s:scene.sections)if(s.owned) {
                Mesh mesh=meshes.get(s);mesh.buffer=buffers.get(s.buffer);
                ByteBuffer vertices=BufferUtils.createByteBuffer(mesh.data.vertices.length);
                vertices.put(mesh.data.vertices).flip();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,mesh.buffer);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,(long)mesh.base*28,vertices);
                ByteBuffer indices=BufferUtils.createByteBuffer(mesh.data.indices.length*mesh.data.indexBytes);
                for(int index:mesh.data.indices)if(mesh.data.indexBytes==2)indices.putShort((short)index);else indices.putInt(index);
                indices.flip();GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER,mesh.indexOffset,indices);
                mesh.data=null;
            }
        } catch(Throwable failure) { close();throw failure; }
    }
    public void begin() { bound=0;GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,elements); }
    public void draw(TerrainSceneData.Section section,boolean textured) {
        Mesh mesh=meshes.get(section);
        if(bound!=mesh.buffer) {
            bound=mesh.buffer;GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,bound);
            GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);
            GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);
            if(textured) {
                GL13.glClientActiveTexture(GL13.GL_TEXTURE0);GL11.glTexCoordPointer(2,GL11.GL_FLOAT,28,16L);
                GL13.glClientActiveTexture(GL13.GL_TEXTURE1);GL11.glTexCoordPointer(2,GL11.GL_SHORT,28,24L);
                GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            }
        }
        GL32.glDrawElementsBaseVertex(GL11.GL_QUADS,section.vertices,mesh.type,mesh.indexOffset,mesh.base);
    }
    @Override public void close() {
        if(elements!=0){GL15.glDeleteBuffers(elements);elements=0;}
        for(int buffer:buffers.values())GL15.glDeleteBuffers(buffer);buffers.clear();meshes.clear();
    }
}
