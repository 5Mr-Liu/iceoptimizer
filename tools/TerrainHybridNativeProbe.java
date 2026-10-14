package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainSubmissionNativeProbe.*;

/** Full mixed-layer comparison, including planning and state restoration.
 * LegacyTerrainArena/LegacyTerrainBatchRenderer are scratch renamed 1.0.20 sources.
 * Optional ASM-instrumented scratch classes only count calls; never used for timing. */
public final class TerrainHybridNativeProbe {
    static final int MESHES=384;
    static final FrameStamp STAMP=new FrameStamp(1,1,new ClientEpochs().snapshot());
    public static final Map<String,Long> calls=new TreeMap<String,Long>();
    public static void count(String name) { Long n=calls.get(name); calls.put(name,n==null?1L:n+1L); }
    public static void main(String[] args) throws Throwable {
        Pbuffer context=new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
            Engine old=new Engine("LegacyTerrainArena"), now=new Engine("LwjglTerrainArena");
            try {
                for (int legacyEvery : new int[]{0,8,2,1}) {
                    old.scene(legacyEvery); now.scene(legacyEvery);
                    setup(); old.draw(); byte[] reference=pixels();
                    setup(); now.draw(); byte[] actual=pixels();
                    require(Arrays.equals(reference,actual),"old/new pixel scene "+legacyEvery);
                    require(GL11.glGetError()==0,"pixel scene GL error");
                    System.out.println("mixed_scene="+legacyEvery+" meshes="+MESHES+" pixels_exact=true");
                    if(Boolean.getBoolean("ice.countGl")) {
                        setup(); calls.clear(); old.draw(); System.out.println("baseline_calls="+calls);
                        setup(); calls.clear(); now.draw(); System.out.println("hybrid_calls="+calls);
                    } else {
                        GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(0,0,1,1);
                        for(int i=0;i<96;i++) {old.draw();now.draw();}
                        double[] before=new double[7],after=new double[7];
                        for(int round=0;round<7;round++) {
                            if((round&1)==0) {before[round]=time(old);after[round]=time(now);}
                            else {after[round]=time(now);before[round]=time(old);}
                        }
                        Arrays.sort(before);Arrays.sort(after);
                        System.out.println("layer_glfinish_us baseline="+Arrays.toString(before)
                            +" hybrid="+Arrays.toString(after)+" median_ratio="+after[3]/before[3]);
                        GL11.glDisable(GL11.GL_SCISSOR_TEST);
                    }
                }
            } finally { old.close(); now.close(); }
            System.out.println("vendor="+GL11.glGetString(GL11.GL_VENDOR));
            System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER));
            System.out.println("version="+GL11.glGetString(GL11.GL_VERSION));
            ContextCapabilities caps=GLContext.getCapabilities();
            System.out.println("GL_NV_bindless_multi_draw_indirect="+caps.GL_NV_bindless_multi_draw_indirect);
            System.out.println("GL_NV_vertex_buffer_unified_memory="+caps.GL_NV_vertex_buffer_unified_memory);
            System.out.println("GL_NV_shader_buffer_load="+caps.GL_NV_shader_buffer_load);
        } finally {context.destroy();}
    }
    static double time(Engine engine) throws Throwable {
        GL11.glFinish(); long start=System.nanoTime();
        for(int i=0;i<128;i++) engine.draw();
        GL11.glFinish(); return (System.nanoTime()-start)/128000.0;
    }
    static MethodHandle method(Class<?> type,Object value,String name,Class<?>...args) throws Exception {
        return MethodHandles.lookup().unreflect(type.getMethod(name,args)).bindTo(value);
    }
    static final class Engine {
        final CacheBudget budget=new CacheBudget(16L<<20,16L<<20,64L<<20);
        final RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
        final ResourceLedger ledger=ledger(guard,budget);
        final TestChunk[] nativeChunks=new TestChunk[16], externalChunks=new TestChunk[16];
        final Object arena;
        final MethodHandle render,upload,release,close;
        final Container container=new Container();
        final ArrayList<RenderChunk> source=new ArrayList<RenderChunk>(MESHES);
        long frame;
        Engine(String name) throws Throwable {
            Class<?> type=Class.forName("dev.rlcraft.ice.optimizer.render.terrain."+name);
            arena=type.getConstructor(RenderThreadGuard.class,ResourceLedger.class,CacheBudget.class,
                CapabilityReport.class,long.class,long.class).newInstance(guard,ledger,budget,
                    CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).build(),32L<<20,1L);
            method(type,arena,"configureNativeBatching",boolean.class,boolean.class).invokeExact(true,false);
            render=method(type,arena,"render",Object.class,BlockRenderLayer.class,boolean.class,boolean.class,long.class);
            upload=method(type,arena,"upload",TerrainUploadContext.Value.class,BufferBuilder.class,
                VertexBuffer.class,FrameStamp.class,boolean.class,boolean.class,boolean.class);
            release=method(type,arena,"release",VertexBuffer.class);
            close=method(type,arena,"close",boolean.class);
            for(int i=0;i<16;i++) {
                nativeChunks[i]=chunk();externalChunks[i]=chunk();
                BufferBuilder builder=builder(-.85F+i*.02F,.85F-i*.02F,50+i*10,220-i*10,30+i*8,1);
                TerrainUploadContext.begin(BlockRenderLayer.SOLID,nativeChunks[i],nativeChunks[i].compiled);
                boolean accepted=(boolean)upload.invokeExact(TerrainUploadContext.take(),builder,
                    (VertexBuffer)nativeChunks[i].vbo,STAMP,false,false,false);
                require(accepted,"native upload");
                TestBuffer vbo=externalChunks[i].vbo;vbo.id=GL15.glGenBuffers();vbo.count=4;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo.id);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                    builder(-.9F+i*.02F,.9F-i*.02F,230-i*10,10+i*10,180-i*8,1).getByteBuffer(),GL15.GL_STATIC_DRAW);
            }
        }
        void scene(int legacyEvery) {
            source.clear();
            for(int i=0;i<MESHES;i++) source.add(i>0 && legacyEvery>0 && i%legacyEvery==0
                ? externalChunks[i%16] : nativeChunks[i%16]);
        }
        void draw() throws Throwable {
            List<RenderChunk> visible=container.ice$renderChunks();
            for(int i=0;i<source.size();i++) visible.add(source.get(i));
            boolean rendered=(boolean)render.invokeExact((Object)container,BlockRenderLayer.SOLID,true,true,++frame);
            require(rendered,"render rejected");
        }
        void close() throws Throwable {
            for(TestChunk chunk:externalChunks) {release.invokeExact((VertexBuffer)chunk.vbo);GL15.glDeleteBuffers(chunk.vbo.id);}
            close.invokeExact(true);GL11.glFinish();ledger.destroyAll(1L);
            require(budget.snapshot().getHeapUsed()==0L && budget.snapshot().getDirectUsed()==0L
                && budget.snapshot().getGpuUsed()==0L,"ledger cleanup");
        }
    }
}
