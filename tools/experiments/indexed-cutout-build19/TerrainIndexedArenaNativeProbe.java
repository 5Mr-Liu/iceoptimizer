package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.*;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Real production upload/draw path; the reference owns ordinary array meshes. */
public final class TerrainIndexedArenaNativeProbe {
    static final long MIB=1024L*1024L, PAGE=16*MIB-(16*MIB)%112;
    static final FrameStamp STAMP=new FrameStamp(1,1,new ClientEpochs().snapshot());
    static long frame;
    static final BlockRenderLayer LAYER=BlockRenderLayer.CUTOUT_MIPPED;
    static int texture, sentinel, vao;
    public static void main(String[] args)throws Exception {
        Pbuffer context=new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
            sentinel=GL15.glGenBuffers();vao=GL30.glGenVertexArrays();texture=GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            ByteBuffer colors=BufferUtils.createByteBuffer(8*8*4);
            for(int y=0;y<8;y++)for(int x=0;x<8;x++)colors.put((byte)(x*31)).put((byte)(y*31)).put((byte)173).put((byte)((x+y)%3==0?0:255));
            colors.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,8,8,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,colors);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            for(boolean persistent:new boolean[]{false,true}) run(persistent);
            GL11.glDeleteTextures(texture);GL15.glDeleteBuffers(sentinel);GL30.glDeleteVertexArrays(vao);
            require(GL11.glGetError()==0,"final GL error");
            System.out.println("production_indexed_arena=true renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally {context.destroy();}
    }
    static void run(boolean persistent)throws Exception {
        Pair plain=new Pair(false,persistent), indexed=new Pair(true,persistent);
        try {
            require(!plain.mesh(0).isIndexed()&&indexed.mesh(0).isIndexed(),"encoding selection");
            require(!indexed.mesh(2).isIndexed(),"small mesh must remain plain");
            int[][] plans={{0,1,0,1},{0,2,1,2,0},{0,1,3,3,1,0},{0,4,1,4,2}};
            for(int view=0;view<24;view++)for(int[] plan:plans)for(boolean batched:new boolean[]{false,true}) {
                byte[] expected=draw(plain,plan,view,batched);
                require(Arrays.equals(expected,draw(indexed,plan,view,batched)),"pixel mismatch view="+view+" batched="+batched);
            }
            // Queued draw still sees the old storage after indexed/plain transitions.
            int[] plan={0,1,2,0};byte[] old=draw(plain,plan,0,true);
            setup(0);indexed.draw(plan,0,true);
            indexed.upload(0,1,71); // indexed -> plain, old draw remains pending
            require(!indexed.mesh(0).isIndexed(),"indexed to plain");
            require(Arrays.equals(old,TerrainOrderedRangesNativeProbe.pixels()),"queued storage overwritten");
            plain.upload(0,1,71);
            require(Arrays.equals(draw(plain,plan,0,true),draw(indexed,plan,0,true)),"indexed/plain transition");
            indexed.upload(0,32,99);plain.upload(0,32,99);
            require(indexed.mesh(0).isIndexed(),"plain to indexed");
            require(Arrays.equals(draw(plain,plan,3,true),draw(indexed,plan,3,true)),"plain/indexed transition");
            // A shader shadow copy and a context lacking 3.2 must retain array storage.
            RenderChunk shader=vanilla(0);BufferBuilder source=builder(shader,32,33);
            TerrainUploadContext.begin(LAYER,shader,shader.getCompiledChunk());
            require(indexed.arena.upload(TerrainUploadContext.take(),source,shader.getVertexBufferByLayer(LAYER.ordinal()),STAMP,false,false,true),"shadow upload");
            require(!mesh(indexed.arena,shader).isIndexed(),"shadow indexed unexpectedly");indexed.arena.release(shader.getVertexBufferByLayer(LAYER.ordinal()));
            Field version=ContextCapabilities.class.getField("OpenGL32");version.setAccessible(true);boolean saved=version.getBoolean(GLContext.getCapabilities());
            try {version.setBoolean(GLContext.getCapabilities(),false);indexed.upload(2,32,19);require(!indexed.mesh(2).isIndexed(),"missing 3.2 accepted");}
            finally {version.setBoolean(GLContext.getCapabilities(),saved);}
            require(indexed.arena.getIndexedUploads()>0&&indexed.arena.getIndexedDrawCalls()>0,"indexed path unused");
            require(indexed.arena.getLiveIndexBytes()>0&&indexed.arena.getIndexedSavedBytes()>0,"missing index accounting");
            System.out.println("persistent="+persistent+" exact_pixel_cases=195 mixed_pages=true mixed_formats=true legacy_barrier=true ebo_vao_restart_matrix_restored=true queued_replacement=true fallback_formats=true");
        } finally {plain.close();indexed.close();}
    }
    static byte[] draw(Pair pair,int[] plan,int view,boolean batched)throws Exception {
        setup(view);FloatBuffer before=BufferUtils.createFloatBuffer(16),after=BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,before);int depth=GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        pair.draw(plan,view,batched);GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,after);
        require(before.equals(after)&&depth==GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH),"matrix state leaked");
        require(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==vao,"VAO leaked");
        require(GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)==sentinel,"EBO leaked");
        require(GL11.glIsEnabled(GL31.GL_PRIMITIVE_RESTART)==(view%2==1),"restart leaked");
        require(GL11.glIsEnabled(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX)==(view%3==1),"fixed restart leaked");
        require(GL11.glGetInteger(GL31.GL_PRIMITIVE_RESTART_INDEX)==2,"restart index changed");
        require(GL11.glGetError()==0,"draw GL error");return TerrainOrderedRangesNativeProbe.pixels();
    }
    static void setup(int view) {
        TerrainOrderedRangesNativeProbe.setupBlend();GL30.glBindVertexArray(vao);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        for(int unit=1;unit>=0;unit--){GL13.glClientActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);}
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glAlphaFunc(GL11.GL_GREATER,.1F);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,sentinel);GL31.glPrimitiveRestartIndex(2);
        if(view%2==1)GL11.glEnable(GL31.GL_PRIMITIVE_RESTART);else GL11.glDisable(GL31.GL_PRIMITIVE_RESTART);
        if(view%3==1)GL11.glEnable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);else GL11.glDisable(GL43.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        GL11.glRotatef(view*3,0,0,1);
        dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker.invalidate();
    }
    static final class Pair implements AutoCloseable {
        final CacheBudget budget=new CacheBudget(8*MIB,8*MIB,40*MIB);
        final RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
        final ResourceLedger ledger=TerrainOrderedRangesNativeProbe.ledger(guard,budget);
        final LwjglTerrainArena arena=new LwjglTerrainArena(guard,ledger,budget,CapabilityReport.builder().pass(ModernCapability.PERSISTENT_MAPPING).build(),32*MIB,1);
        final RenderChunk[] chunks=new RenderChunk[5];final boolean persistent;final boolean indexed;
        ArenaRange filler;int legacyId;
        Pair(boolean indexed,boolean persistent)throws Exception {
            this.indexed=indexed;this.persistent=persistent;arena.configureNativeBatching(indexed,false);
            for(int i=0;i<4;i++)chunks[i]=vanilla(i==3?256:0);
            upload(0,32,10);GpuArenaAllocator allocator=(GpuArenaAllocator)field(arena,"allocator");
            filler=allocator.allocate(PAGE-mesh(0).getRange().getLength());
            upload(1,32,50);upload(2,1,100);upload(3,32,150);
            TerrainOrderedRangesNativeProbe.TestChunk legacy=TerrainOrderedRangesNativeProbe.chunk();chunks[4]=legacy;
            legacy.vbo.id=legacyId=GL15.glGenBuffers();legacy.vbo.count=128;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,legacyId);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,builder(legacy,32,200).getByteBuffer(),GL15.GL_STATIC_DRAW);
        }
        void upload(int index,int quads,int shade)throws Exception {
            RenderChunk chunk=chunks[index];arena.configureNativeBatching(indexed,false);
            TerrainUploadContext.begin(LAYER,chunk,chunk.getCompiledChunk());
            require(arena.upload(TerrainUploadContext.take(),builder(chunk,quads,shade),chunk.getVertexBufferByLayer(LAYER.ordinal()),STAMP,persistent,persistent,false),"upload rejected");
        }
        TerrainMesh mesh(int index)throws Exception{return TerrainIndexedArenaNativeProbe.mesh(arena,chunks[index]);}
        void draw(int[] plan,int view,boolean batched) {
            RenderChunk[] selected=new RenderChunk[plan.length];for(int i=0;i<plan.length;i++)selected[i]=chunks[plan[i]];
            TerrainOrderedRangesNativeProbe.Container container=new TerrainOrderedRangesNativeProbe.Container(selected);
            container.viewX=view%2==0?.125:256.125;container.viewY=.1;container.viewZ=-.2;
            arena.configureNativeBatching(true,false);
            require(arena.render(container,LAYER,batched,true,++frame),"render declined");
        }
        public void close() {
            arena.close(true);GL11.glFinish();ledger.destroyAll(1);if(legacyId!=0)GL15.glDeleteBuffers(legacyId);
            require(budget.snapshot().getGpuUsed()==0&&budget.snapshot().getHeapUsed()==0&&budget.snapshot().getDirectUsed()==0,"budget leaked");
        }
    }
    static TerrainMesh mesh(LwjglTerrainArena arena,RenderChunk chunk)throws Exception {
        return (TerrainMesh)field(((Map<?,?>)field(arena,"meshes")).get(chunk.getVertexBufferByLayer(LAYER.ordinal())),"mesh");
    }
    static RenderChunk vanilla(int x)throws Exception {
        Method method=TerrainOrderedRangesNativeProbe.class.getDeclaredMethod("vanillaChunk",int.class);method.setAccessible(true);return (RenderChunk)method.invoke(null,x);
    }
    static BufferBuilder builder(RenderChunk chunk,int quads,int shade) {
        int x=chunk.getPosition().getX();BufferBuilder builder=new BufferBuilder(quads*112);
        builder.setTranslation(-x,0,0);builder.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
        for(int q=0;q<quads;q++)for(int i=0;i<4;i++) {
            double xx=(i==1||i==2?.55:-.85)+(q%4)*.08, yy=(i>=2?.7:-.7)+(q%4)*.03;
            builder.pos(x+xx,yy,0).color((shade+q%4*37)%256,230-shade,87,128).tex(i==1||i==2?1:0,i>=2?1:0).lightmap(0,0).endVertex();
        }
        builder.finishDrawing();return builder;
    }
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void require(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
}
