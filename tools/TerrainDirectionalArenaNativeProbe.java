package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.util.*;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainOrderedRangesNativeProbe.*;

/** Production directional emitter versus the same uploaded geometry without CPU
 * direction omission, including mixed ownership, matrix restoration and reuse. */
public final class TerrainDirectionalArenaNativeProbe {
    private static final FrameStamp STAMP=new FrameStamp(1,1,new ClientEpochs().snapshot());
    private static long frame;
    public static void main(String[] args)throws Exception {
        Pbuffer context=new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
            int comparisons=0;
            for(boolean persistent:new boolean[]{false,true}) {
                CacheBudget budget=new CacheBudget(8L<<20,8L<<20,40L<<20);
                RenderThreadGuard guard=RenderThreadGuard.captureCurrent();ResourceLedger ledger=ledger(guard,budget);
                LwjglTerrainArena arena=new LwjglTerrainArena(guard,ledger,budget,
                    CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).pass(ModernCapability.PERSISTENT_MAPPING).build(),32L<<20,1);
                RenderChunk a=vanillaChunk(0),b=vanillaChunk(0),plain=vanillaChunk(0);
                TestChunk legacy=chunk();
                try {
                    arena.configureNativeBatching(true,true);upload(arena,a,1,persistent);upload(arena,b,2,persistent);
                    arena.configureNativeBatching(true,false);upload(arena,plain,3,persistent);
                    legacy.vbo.id=GL15.glGenBuffers();legacy.vbo.count=4;
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,legacy.vbo.id);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER,builder(-.7F,.5F,30,170,220,1).getByteBuffer(),GL15.GL_STATIC_DRAW);
                    for(int replacement=0;replacement<2;replacement++) {
                        if(replacement!=0) {arena.configureNativeBatching(true,true);upload(arena,a,4,persistent);}
                        for(int view=0;view<24;view++)for(int mode=0;mode<3;mode++) {
                            Container list=new Container(a,b,plain,legacy,b,a);
                            view(view,mode);arena.configureNativeBatching(false,false);
                            require(arena.render(list.copy(),BlockRenderLayer.SOLID,false,false,++frame),"reference declined");
                            byte[] reference=pixels();
                            view(view,mode);arena.configureNativeBatching(true,true);
                            java.nio.FloatBuffer before=BufferUtils.createFloatBuffer(16),after=BufferUtils.createFloatBuffer(16);
                            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,before);
                            int depth=GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
                            require(arena.render(list.copy(),BlockRenderLayer.SOLID,true,false,++frame),"directional declined");
                            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,after);
                            require(before.equals(after)&&depth==GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH),"matrix not restored");
                            require(Arrays.equals(reference,pixels()),"directional pixels changed view="+view+" mode="+mode);
                            require(GL11.glGetError()==0,"directional GL error");comparisons++;
                        }
                    }
                    require(arena.getDirectionalCulledVertices()>0,"CPU direction path not exercised");
                    System.out.println("persistent="+persistent+" omitted_vertices="+arena.getDirectionalCulledVertices());
                } finally {
                    arena.release(legacy.vbo);GL15.glDeleteBuffers(legacy.vbo.id);
                    arena.close(true);GL11.glFinish();ledger.destroyAll(1);
                }
                require(budget.snapshot().getGpuUsed()==0&&budget.snapshot().getDirectUsed()==0&&budget.snapshot().getHeapUsed()==0,"budget leaked");
            }
            System.out.println("production_directional_exact_pixels="+comparisons+" mixed_plain_legacy=true replaced_upload=true state_fallbacks=true");
        } finally {context.destroy();}
    }
    private static void view(int view,int mode) {
        setup();GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glOrtho(-2,2,-2,2,-20,20);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
        GL11.glTranslatef(0,0,-4);GL11.glRotatef((view%3-1)*25,1,0,0);GL11.glRotatef(view*15,0,1,0);
        if(mode!=1)GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);GL11.glFrontFace(mode==2?GL11.GL_CW:GL11.GL_CCW);
    }
    private static void upload(LwjglTerrainArena arena,RenderChunk chunk,int tag,boolean persistent) {
        BufferBuilder builder=new BufferBuilder(1<<18);builder.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
        for(int face=0;face<7;face++) {
            int repeats=face==3?600:face==1?2:8;
            for(int repeat=0;repeat<repeats;repeat++)quad(builder,face,tag);
        }
        builder.finishDrawing();TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.getCompiledChunk());
        require(arena.upload(TerrainUploadContext.take(),builder,chunk.getVertexBufferByLayer(0),STAMP,persistent,persistent,false),"upload rejected");
    }
    private static void quad(BufferBuilder builder,int face,int tag) {
        int real=face==0?4:face;boolean positive=real==2||real==4||real==6;
        int[][] corners=positive?new int[][]{{0,0},{1,0},{1,1},{0,1}}:new int[][]{{0,0},{0,1},{1,1},{1,0}};
        for(int i=0;i<4;i++) {
            float u=corners[i][0]-.5F,v=corners[i][1]-.5F,plane=positive?.5F:-.5F;
            float x,y,z;
            if(real<=2){x=v;y=plane;z=u;}else if(real<=4){x=u;y=v;z=plane;}else{x=plane;y=u;z=v;}
            if(face==0&&i==2)z+=.125F;
            builder.pos(x,y,z).color(35+real*25,40+tag*30,220-real*20,255).tex(corners[i][0],corners[i][1]).lightmap(0,0).endVertex();
        }
    }
}
