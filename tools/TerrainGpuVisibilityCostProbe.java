package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainGpuVisibility;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Equal-scene complete CPU-filter versus GPU packet/export/dispatch comparison. */
public final class TerrainGpuVisibilityCostProbe {
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final FrameStamp STAMP=new FrameStamp(1,1,new ClientEpochs().snapshot());
    private static volatile long sink;
    public static void main(String[] args)throws Exception {
        Pbuffer context=new Pbuffer(64,64,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            CacheBudget budget=new CacheBudget(4L<<20,4L<<20,4L<<20);
            ResourceLedger ledger=new ResourceLedger(RenderThreadGuard.captureCurrent(),budget,(kind,id)->{
                if(kind==RenderResourceKind.BUFFER)GL15.glDeleteBuffers(id);
                else if(kind==RenderResourceKind.PROGRAM)GL20.glDeleteProgram(id);
            },128);
            LwjglTerrainGpuVisibility gpu=new LwjglTerrainGpuVisibility(ledger,budget);
            int commandBuffer=GL15.glGenBuffers();
            try {
                for(int count:new int[]{512,1536,4096}) {
                    scene(gpu,commandBuffer,count,0);
                    scene(gpu,commandBuffer,count,1);
                }
                scene(gpu,commandBuffer,2048,2);
            } finally {gpu.close(true);GL11.glFinish();ledger.destroyAll(1);GL15.glDeleteBuffers(commandBuffer);}
            if(budget.snapshot().getGpuUsed()!=0)throw new AssertionError("budget leak");
            if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new AssertionError("GL error");
        } finally {context.destroy();}
        System.out.println("visibility_cost_probe_complete=true sink="+sink);
    }
    private static void scene(LwjglTerrainGpuVisibility gpu,int commands,int count,int mode)throws Exception {
        CurrentFrameTerrainOcclusion software=new CurrentFrameTerrainOcclusion();
        Class<?> containerClass=Class.forName(SoftwareTerrainOcclusionBenchmark.class.getName()+"$Container");
        Constructor<?> constructor=containerClass.getDeclaredConstructor();constructor.setAccessible(true);
        TerrainRenderListAccessor view=(TerrainRenderListAccessor)constructor.newInstance();
        List<RenderChunk> original=new ArrayList<RenderChunk>();
        Method add=SoftwareTerrainOcclusionBenchmark.class.getDeclaredMethod("add",CurrentFrameTerrainOcclusion.class,
            List.class,float.class,float.class,float.class,float.class,float.class);add.setAccessible(true);
        if(mode==1)add.invoke(null,software,original,-.98f,-.98f,.98f,.98f,-.8f);
        for(int i=original.size();i<count;i++) {
            float x=-.8f+(i%64)*.025f,y=-.8f+(i/64)*.025f;
            if(mode==2)add.invoke(null,software,original,-1.1f,-1.1f,1.1f,1.1f,.1f+(i%8)*.01f);
            else add.invoke(null,software,original,x,y,x+.004f,y+.004f,.1f+(i%8)*.01f);
        }
        RenderMatrixBridge.capture(FloatBuffer.wrap(IDENTITY),FloatBuffer.wrap(IDENTITY),IntBuffer.wrap(new int[]{0,0,1920,1080}));
        RenderMatrixBridge.Snapshot matrices=RenderMatrixBridge.snapshot();
        Task cpu=()->{sink+=software.filter(view,BlockRenderLayer.SOLID,matrices);};
        for(int i=0;i<500;i++){reset(view,original);cpu.run();}
        long[] cpuTimes=new long[500];for(int i=0;i<cpuTimes.length;i++){
            reset(view,original); // Restore benchmark input outside the measured filter operation.
            long t=System.nanoTime();cpu.run();cpuTimes[i]=System.nanoTime()-t;
        }
        Arrays.sort(cpuTimes);
        ByteBuffer bounds=BufferUtils.createByteBuffer(count*64),faces=BufferUtils.createByteBuffer(2048*128);
        ByteBuffer draw=BufferUtils.createByteBuffer(count*16);
        for(int i=0;i<count;i++)draw.putInt(4).putInt(1).putInt(0).putInt(i);draw.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,commands);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,draw,GL15.GL_STREAM_DRAW);
        view.ice$renderChunks().clear();view.ice$renderChunks().addAll(original);
        Task next=()->{int sourceCount=software.writeGpuInput(view,bounds,faces,count,2048);
            if(!gpu.cull(STAMP,IDENTITY,IDENTITY,bounds,count,faces,sourceCount,commands,0,16,count))throw new AssertionError("GPU unavailable");};
        for(int i=0;i<1200;i++)next.run();GL11.glFinish();
        int query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);
        long[] hostTimes=new long[200];for(int i=0;i<hostTimes.length;i++){long t=System.nanoTime();next.run();hostTimes[i]=System.nanoTime()-t;}
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);long elapsed=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT);GL15.glDeleteQueries(query);
        Arrays.sort(hostTimes);
        System.out.printf(java.util.Locale.ROOT,"same_scene count=%d mode=%s cpu_filter_p50_us=%.2f cpu_filter_p95_us=%.2f gpu_export_submit_p50_us=%.2f gpu_export_submit_p95_us=%.2f gpu_queue_elapsed_us=%.2f%n",
            count,mode==0?"open":mode==1?"occluded":"overlapping-full-walls",cpuTimes[250]/1000.0,cpuTimes[475]/1000.0,hostTimes[100]/1000.0,hostTimes[190]/1000.0,elapsed/200000.0);
    }
    private static void reset(TerrainRenderListAccessor view,List<RenderChunk> original) {
        view.ice$renderChunks().clear();view.ice$renderChunks().addAll(original);
    }
    private interface Task {void run()throws Exception;}
}
