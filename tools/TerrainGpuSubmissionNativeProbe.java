package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.*;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.visibility.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.vertex.*;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import static dev.rlcraft.ice.optimizer.render.terrain.TerrainSubmissionNativeProbe.*;

/** Real pixels through Arena -> evidence packet -> compute -> 16/112-byte MDI. */
public final class TerrainGpuSubmissionNativeProbe {
    private static final FrameStamp STAMP = new FrameStamp(1,1,new ClientEpochs().snapshot());
    private static long frame;
    public static void main(String[] args) throws Exception {
        Pbuffer context=new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
            if(args.length>0 && "standard".equals(args[0])) {
                Field cap=ContextCapabilities.class.getField("GL_NV_bindless_multi_draw_indirect");
                cap.setAccessible(true);cap.setBoolean(GLContext.getCapabilities(),false);
            }
            run(false); run(true);
            System.out.println("terrain_compute_submission_native=true renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally {context.destroy();}
    }
    private static void run(boolean external) throws Exception {
        CacheBudget budget=new CacheBudget(16L<<20,16L<<20,32L<<20);
        RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
        ResourceLedger ledger=ledger(guard,budget);
        LwjglTerrainArena arena=new LwjglTerrainArena(guard,ledger,budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).build(),16L<<20,1);
        CurrentFrameTerrainOcclusion evidence=new CurrentFrameTerrainOcclusion();
        TestChunk wall=chunk(),behind=chunk();
        try {
            arena.configureNativeBatching(true,false);
            BufferBuilder wallData=quad(-.9F,.9F,0,40,180,70);
            stage(evidence,wall,wallData);
            upload(arena,wall,wallData);
            BufferBuilder behindData=quad(-.12F,.12F,.6F,230,20,20);
            stage(evidence,behind,behindData);
            if(external) {
                behind.vbo.id=GL15.glGenBuffers();behind.vbo.count=4;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,behind.vbo.id);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,behindData.getByteBuffer(),GL15.GL_STATIC_DRAW);
            } else upload(arena,behind,behindData);
            Container scene=new Container(wall,behind);
            LwjglTerrainBatchRenderer batches=(LwjglTerrainBatchRenderer)field(arena,"nativeBatches");
            for(int view=0;view<24;view++) {
                scene.viewX=.15D*Math.sin(view*.19D);
                RenderMatrixBridge.Snapshot matrices=setupView(view);
                arena.configureVisibility(STAMP,null,null);
                require(arena.render(scene.copy(),BlockRenderLayer.SOLID,true,true,++frame),"unculled render");
                byte[] expected=pixels();
                setupView(view);
                arena.configureVisibility(STAMP,evidence,matrices);
                require(arena.render(scene.copy(),BlockRenderLayer.SOLID,true,true,++frame),"compute render");
                require(Arrays.equals(expected,pixels()),"compute changed pixels view="+view+" external="+external);
                require(batches.commandStrideBytes()==(external&&batches.supportsCrossPage()?112:16),"command layout selection");
                require(instance(batches,0)==1 && instance(batches,1)==0,"covered command was not culled");
            }
            evidence.release(wall.vbo);
            RenderMatrixBridge.Snapshot matrices=setupView(0);
            arena.configureVisibility(STAMP,evidence,matrices);
            require(arena.render(scene.copy(),BlockRenderLayer.SOLID,true,true,++frame),"fresh evidence render");
            require(instance(batches,1)==1,"stale source coverage survived release");
            require(GL11.glGetError()==0,"combined compute/draw GL error");
            System.out.println("arena_compute external="+external+" command_stride="+batches.commandStrideBytes()
                +" moving_views=24 pixels_exact=true gpu_instance_count=0 evidence_release_refresh=true");
        } finally {
            arena.release(wall.vbo);arena.release(behind.vbo);
            if(external && behind.vbo.id!=0)GL15.glDeleteBuffers(behind.vbo.id);
            arena.close(true);GL11.glFinish();ledger.destroyAll(1);
        }
        require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0
            && budget.snapshot().getHeapUsed()==0,"combined path budget leak");
    }
    private static int instance(LwjglTerrainBatchRenderer batches,int index) {
        IntBuffer out=BufferUtils.createIntBuffer(1);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,batches.commandBufferId());
        GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER,(long)batches.commandBaseBytes()
            +index*batches.commandStrideBytes()+4,out);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,0);
        return out.get(0);
    }
    private static RenderMatrixBridge.Snapshot setupView(int view) {
        setup();GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glRotatef(view*.3F,0,0,1);
        FloatBuffer model=BufferUtils.createFloatBuffer(16),projection=BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,model);GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX,projection);
        RenderMatrixBridge.capture(model,projection,IntBuffer.wrap(new int[]{0,0,64,64}));
        return RenderMatrixBridge.snapshot();
    }
    private static BufferBuilder quad(float min,float max,float z,int r,int g,int b) {
        BufferBuilder builder=new BufferBuilder(112);
        builder.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
        for(int i=0;i<4;i++)builder.pos(i==1||i==2?max:min,i>=2?max:min,z).color(r,g,b,255)
            .tex(i==1||i==2?.9:.1,i>=2?.9:.1).lightmap(0,0).endVertex();
        builder.finishDrawing();return builder;
    }
    private static void upload(LwjglTerrainArena arena,TestChunk chunk,BufferBuilder builder) {
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.compiled);
        require(arena.upload(TerrainUploadContext.take(),builder,chunk.vbo,STAMP,false,false,false),"arena upload");
    }
    @SuppressWarnings("unchecked")
    private static void stage(CurrentFrameTerrainOcclusion target,TestChunk chunk,BufferBuilder builder) throws Exception {
        Class<?> meshType=Class.forName("dev.rlcraft.ice.optimizer.render.visibility.TerrainOccluderMesh");
        Class<?> opacity=Class.forName(meshType.getName()+"$Opacity");
        Object opaque=Proxy.newProxyInstance(opacity.getClassLoader(),new Class<?>[]{opacity},(proxy,method,args)->true);
        Method capture=meshType.getDeclaredMethod("capture",ByteBuffer.class,int.class,int.class,opacity);
        capture.setAccessible(true);
        Object mesh=capture.invoke(null,builder.getByteBuffer(),28,builder.getVertexCount(),opaque);
        require(mesh!=null,"opaque mesh capture");
        Class<?> entry=Class.forName(CurrentFrameTerrainOcclusion.class.getName()+"$Entry");
        Constructor<?> constructor=entry.getDeclaredConstructor(RenderChunk.class,CompiledChunk.class,meshType,TerrainVertexSpace.class);
        constructor.setAccessible(true);
        Object value=constructor.newInstance(chunk,chunk.compiled,mesh,TerrainVertexSpace.capture(builder,chunk));
        ((Map<Object,Object>)field(target,"pending")).put(chunk.vbo,value);
        target.uploaded(chunk.vbo);
    }
    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
}
