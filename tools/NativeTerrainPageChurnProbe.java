package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offscreen upload/unload/rebuild workload; does not launch Minecraft. */
public final class NativeTerrainPageChurnProbe {
    private static final int LIVE=256, FRAMES=600, VERTICES=4096;
    public static void main(String[] args) throws Exception {
        Pbuffer context=new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent(); NativeTerrainStartup.initialize();
            System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER)+" version="+GL11.glGetString(GL11.GL_VERSION));
            for(int stride:new int[]{28,56})run(stride);
        } finally {context.destroy();}
    }

    private static void run(int stride) {
        NativeTerrainPages21 pages=new NativeTerrainPages21(stride);
        ByteBuffer source=BufferUtils.createByteBuffer(VERTICES*stride);
        for(int vertex=0;vertex<VERTICES;vertex++) {
            int corner=vertex&3, at=vertex*stride;
            source.putFloat(at,corner==1||corner==2?0.5F:-0.5F);
            source.putFloat(at+4,corner>=2?0.5F:-0.5F);
        }
        NativeTerrainAllocation[] meshes=new NativeTerrainAllocation[LIVE];
        long[] frameNs=new long[FRAMES];
        long peak=0;int peakPages=0,created=0,retired=0;
        long createMax=0,reuseMax=0;
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnable(GL11.GL_CULL_FACE);GL11.glCullFace(GL11.GL_FRONT);
        try {
            for(int frame=0;frame<FRAMES;frame++) {
                long start=System.nanoTime();
                // Alternate full sweeps and a moving frontier; replacements
                // upload while the old mesh is still live, like the renderer.
                if(frame%100==0) for(int n=0;n<LIVE;n++) if(meshes[n]!=null) {
                    pages.free(meshes[n]);meshes[n]=null;
                }
                int updates=frame%100<16?16:8;
                for(int update=0;update<updates;update++) {
                    int slot=(frame*16+update)%LIVE, before=pages.pageCount();
                    long uploadStart=System.nanoTime();
                    NativeTerrainAllocation replacement=pages.upload(source,VERTICES);
                    long uploadNs=System.nanoTime()-uploadStart;
                    int after=pages.pageCount();
                    if(after>before){created+=after-before;createMax=Math.max(createMax,uploadNs);}
                    else reuseMax=Math.max(reuseMax,uploadNs);
                    peak=Math.max(peak,pages.capacityBytes());peakPages=Math.max(peakPages,after);
                    if(meshes[slot]!=null)pages.free(meshes[slot]);
                    retired+=Math.max(0,after-pages.pageCount());meshes[slot]=replacement;
                }
                NativeTerrainPages21.Page bound=null;
                for(NativeTerrainAllocation mesh:meshes)if(mesh!=null) {
                    int address=mesh.vertexFirst(0);
                    NativeTerrainPages21.Page page=pages.page(address);
                    if(page!=bound){GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,page.vertices);bound=page;}
                    GL11.glVertexPointer(3,GL11.GL_FLOAT,stride,NativeTerrainPages21.local(address)*(long)stride);
                    // Consume each resident vertex without measuring fill rate.
                    GL11.glDrawArrays(GL11.GL_QUADS,0,VERTICES);
                }
                // Finish is probe-only: include outstanding upload/draw work in
                // the frame sample, never claim this is actual game frame time.
                GL11.glFinish(); frameNs[frame]=System.nanoTime()-start;
                if(GL11.glGetError()!=0)throw new AssertionError("GL error during churn");
            }
            for(NativeTerrainAllocation mesh:meshes)if(mesh!=null)pages.free(mesh);
            if(pages.capacityBytes()!=0||pages.pageCount()!=0)throw new AssertionError("Leaked empty pages");
            if(peakPages>10)throw new AssertionError("Unbounded page growth: "+peakPages);
            Arrays.sort(frameNs);
            System.out.printf(Locale.ROOT,"CHURN stride=%d frames=%d peak_pages=%d peak_gpu_buffer_bytes=%d final_bytes=%d page_creates=%d retire_during_replace=%d frame_p50_ms=%.3f frame_p95_ms=%.3f frame_p99_ms=%.3f frame_max_ms=%.3f allocation_upload_max_ms=%.3f reuse_upload_max_ms=%.3f%n",
                stride,FRAMES,peakPages,peak,pages.capacityBytes(),created,retired,
                ms(frameNs[FRAMES/2]),ms(frameNs[FRAMES*95/100]),ms(frameNs[FRAMES*99/100]),ms(frameNs[FRAMES-1]),ms(createMax),ms(reuseMax));
        } finally {pages.delete();GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);GL11.glDisable(GL11.GL_CULL_FACE);}
    }
    private static double ms(long ns){return ns/1000000.0;}
}
