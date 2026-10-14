package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainVertexSpace;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import sun.misc.Unsafe;

/** Offline CPU workload, not an in-game FPS claim. No OpenGL context or game instance. */
public final class SoftwareTerrainOcclusionBenchmark {
    private static final boolean ADAPTIVE = Boolean.getBoolean("ice.adaptive");
    private static volatile long sink;
    private static final TerrainOccluderMesh.Opacity OPAQUE = new TerrainOccluderMesh.Opacity() {
        @Override public boolean opaque(float a,float b,float c,float d) { return true; }
    };
    public static void main(String[] args) throws Exception {
        benchCapture("capture-16x16-wall",wall(16,16));
        benchCapture("capture-512-faces",wall(32,16));
        benchFilter(512,false,false,false);
        benchFilter(4096,false,false,false);
        benchFilter(512,true,false,false);
        benchFilter(4095,true,false,false);
        benchFilter(128,true,false,true);
        benchFilter(4096,false,true,false);
        System.out.println("checksum="+sink);
    }
    private static void benchCapture(String name,final ByteBuffer bytes) throws Exception {
        measure(name,new Task(){
            @Override public void run(){
                TerrainOccluderMesh mesh=TerrainOccluderMesh.capture(bytes,28,bytes.limit()/28,OPAQUE);
                sink+=mesh.quads.length;
            }
        });
    }
    private static void benchFilter(int count,boolean blocked,boolean fullScreen,boolean largeQueries) throws Exception {
        final CurrentFrameTerrainOcclusion occlusion=new CurrentFrameTerrainOcclusion();
        final Container list=new Container();
        final ArrayList<RenderChunk> original=new ArrayList<RenderChunk>();
        if(blocked) add(occlusion,original,-.98F,-.98F,.98F,.98F,-.8F);
        for(int i=0;i<count;i++){
            int columns=count>512?64:32;
            float spacing=count>512?.025F:.05F;
            float x=-.8F+(i%columns)*spacing,y=(count>512?-.8F:-.4F)+(i/columns)*spacing;
            if(fullScreen) add(occlusion,original,-1.1F,-1.1F,1.1F,1.1F,.1F+(i%8)*.01F);
            else if(largeQueries) add(occlusion,original,-.15F,-.15F,.15F,.15F,.1F+(i%8)*.01F);
            else add(occlusion,original,x,y,x+.004F,y+.004F,.1F+(i%8)*.01F);
        }
        float[] identity=new float[16];identity[0]=identity[5]=identity[10]=identity[15]=1;
        RenderMatrixBridge.capture(FloatBuffer.wrap(identity),FloatBuffer.wrap(identity),
            IntBuffer.wrap(new int[]{0,0,1920,1080}));
        final RenderMatrixBridge.Snapshot matrices=RenderMatrixBridge.snapshot();
        String name="filter-"+(count+(blocked?1:0))+(fullScreen?"-budget-limited":largeQueries?"-wall-large-queries":blocked?"-wall-occluded":"-open");
        measure(name,new Task(){
            @Override public void run(){
                list.values.clear();list.values.addAll(original);
                sink+=ADAPTIVE ? occlusion.filterAdaptive(list,BlockRenderLayer.SOLID,matrices)
                    : occlusion.filter(list,BlockRenderLayer.SOLID,matrices);
            }
        });
        System.out.println("  tested="+occlusion.getTested()+" hidden="+occlusion.getOccluded()
            +" quads="+occlusion.getRasterizedQuads()+" stored="+occlusion.getRetainedBytes());
    }
    private static void measure(String name,Task task)throws Exception{
        for(int i=0;i<1500;i++)task.run();
        long[] samples=new long[3000];
        for(int i=0;i<samples.length;i++){long start=System.nanoTime();task.run();samples[i]=System.nanoTime()-start;}
        Arrays.sort(samples);
        System.out.printf(java.util.Locale.ROOT,"%s p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n",name,
            samples[1500]/1e6,samples[2850]/1e6,samples[2970]/1e6,samples[2999]/1e6);
    }
    private interface Task{void run()throws Exception;}
    private static ByteBuffer wall(int width,int height){
        ByteBuffer data=ByteBuffer.allocate(width*height*112).order(ByteOrder.nativeOrder());
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)quad(data,x,y,x+1,y+1,0);
        data.flip();return data;
    }
    private static void quad(ByteBuffer data,float x0,float y0,float x1,float y1,float z){
        for(int i=0;i<4;i++)data.putFloat(i==1||i==2?x1:x0).putFloat(i>=2?y1:y0).putFloat(z)
            .putInt(-1).putFloat(i==1||i==2?.9F:.1F).putFloat(i>=2?.9F:.1F).putInt(0);
    }
    @SuppressWarnings("unchecked")
    private static void add(CurrentFrameTerrainOcclusion target,List<RenderChunk> list,
                            float x0,float y0,float x1,float y1,float z)throws Exception{
        Field unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Unsafe unsafe=(Unsafe)unsafeField.get(null);
        TestChunk chunk=(TestChunk)unsafe.allocateInstance(TestChunk.class);chunk.compiled=new CompiledChunk();
        ByteBuffer data=ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());quad(data,x0,y0,x1,y1,z);data.flip();
        TerrainOccluderMesh mesh=TerrainOccluderMesh.capture(data,28,4,OPAQUE);
        TerrainVertexSpace space=TerrainVertexSpace.capture(new BufferBuilder(28),chunk);
        Class<?> entry=Class.forName(CurrentFrameTerrainOcclusion.class.getName()+"$Entry");
        Constructor<?> constructor=entry.getDeclaredConstructor(RenderChunk.class,
            CompiledChunk.class,TerrainOccluderMesh.class,TerrainVertexSpace.class);constructor.setAccessible(true);
        Object value=constructor.newInstance(chunk,chunk.compiled,mesh,space);
        Field pending=CurrentFrameTerrainOcclusion.class.getDeclaredField("pending");pending.setAccessible(true);
        VertexBuffer vbo=(VertexBuffer)unsafe.allocateInstance(VertexBuffer.class);
        ((Map<VertexBuffer,Object>)pending.get(target)).put(vbo,value);target.uploaded(vbo);list.add(chunk);
    }
    private static final class TestChunk extends RenderChunk{
        private CompiledChunk compiled;
        private TestChunk(){super(null,null,0);}
        @Override public BlockPos getPosition(){return BlockPos.ORIGIN;}
        @Override public CompiledChunk getCompiledChunk(){return compiled;}
    }
    private static final class Container implements TerrainRenderListAccessor{
        final ArrayList<RenderChunk> values=new ArrayList<RenderChunk>();
        @Override public List<RenderChunk> ice$renderChunks(){return values;}
        @Override public boolean ice$initialized(){return true;}
        @Override public double ice$viewEntityX(){return 0;}
        @Override public double ice$viewEntityY(){return 0;}
        @Override public double ice$viewEntityZ(){return 0;}
    }
}
