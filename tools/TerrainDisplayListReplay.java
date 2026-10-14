package dev.rlcraft.ice.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.*;

/** Frozen-scene diagnostic. Compilation copies geometry; never a production cache. */
public final class TerrainDisplayListReplay implements AutoCloseable {
    public static final class Draw {
        public final int list, vertices;
        public final int[] region;
        Draw(int list, int vertices, int[] region) {
            this.list = list; this.vertices = vertices; this.region = region;
        }
    }
    public final List<Draw> meshes = new ArrayList<Draw>(), regions = new ArrayList<Draw>();
    public final long compileNanos;
    public TerrainDisplayListReplay(TerrainSceneData scene, Map<Integer,Integer> buffers, boolean textured) {
        long start = System.nanoTime();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            for (int unit=0; unit<2; unit++) {
                GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);
                if (textured) GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                else GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            }
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            for (TerrainSceneData.Section section : scene.sections) if (section.owned) {
                bind(section, buffers, textured);
                int id = GL11.glGenLists(1);
                if (id == 0) throw new IllegalStateException("Display-list allocation failed");
                meshes.add(new Draw(id, section.vertices, section.region));
                GL11.glNewList(id, GL11.GL_COMPILE);
                try { GL11.glDrawArrays(GL11.GL_QUADS, (int)(section.offset/28), section.vertices); }
                finally { GL11.glEndList(); }
            }
            // Region lists reference mesh lists instead of copying their geometry again.
            for (int i=0; i<meshes.size();) {
                Draw head=meshes.get(i); int end=i+1, count=head.vertices;
                while (end<meshes.size() && Arrays.equals(head.region, meshes.get(end).region))
                    count += meshes.get(end++).vertices;
                int id=GL11.glGenLists(1);
                if (id == 0) throw new IllegalStateException("Display-list allocation failed");
                regions.add(new Draw(id,count,head.region));
                GL11.glNewList(id,GL11.GL_COMPILE);
                try { for (int j=i; j<end; j++) GL11.glCallList(meshes.get(j).list); }
                finally { GL11.glEndList(); }
                i=end;
            }
            GL11.glFinish();
        } catch (Throwable failure) { close(); throw failure; }
        finally { GL11.glPopClientAttrib(); }
        compileNanos=System.nanoTime()-start;
    }
    private static void bind(TerrainSceneData.Section s,Map<Integer,Integer> buffers,boolean textured) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffers.get(s.buffer));
        GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);
        GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);
        if (textured) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);GL11.glTexCoordPointer(2,GL11.GL_FLOAT,28,16L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);GL11.glTexCoordPointer(2,GL11.GL_SHORT,28,24L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        }
    }
    @Override public void close() {
        for (Draw draw:regions) GL11.glDeleteLists(draw.list,1); regions.clear();
        for (Draw draw:meshes) GL11.glDeleteLists(draw.list,1); meshes.clear();
    }
}
