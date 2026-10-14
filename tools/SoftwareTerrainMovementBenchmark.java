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

/** Actual filter workload under fixed and changing cameras; no game or GL context. */
public final class SoftwareTerrainMovementBenchmark {
    private static volatile long sink;
    private static final TerrainOccluderMesh.Opacity OPAQUE=new TerrainOccluderMesh.Opacity(){
        @Override public boolean opaque(float a,float b,float c,float d){return true;}
    };
    private static final TerrainOccluderMesh.Opacity NO_OCCLUDERS=new TerrainOccluderMesh.Opacity(){
        @Override public boolean opaque(float a,float b,float c,float d){return false;}
    };
    private final CurrentFrameTerrainOcclusion filter=new CurrentFrameTerrainOcclusion();
    private final Container container=new Container();
    private final List<RenderChunk> original=new ArrayList<RenderChunk>();
    private final float[] model=new float[16],projection=new float[16];
    private final FloatBuffer modelBuffer=FloatBuffer.wrap(model),projectionBuffer=FloatBuffer.wrap(projection);
    private final IntBuffer viewport=IntBuffer.wrap(new int[]{0,0,1920,1080});

    private SoftwareTerrainMovementBenchmark() throws Exception {
        projection[0]=projection[5]=1;projection[10]=-100.1F/99.9F;
        projection[11]=-1;projection[14]=-.2F*100F/99.9F;
        ByteBuffer source=ByteBuffer.allocate(224).order(ByteOrder.nativeOrder());
        put(source,new float[]{-8,-8,-4,8,-8,-4,8,8,-4,-8,8,-4});
        put(source,new float[]{-8,-8,-16,8,-8,-16,8,8,0,-8,8,0});
        source.flip();add(TerrainOccluderMesh.capture(source,28,8,OPAQUE));
        for(int i=0;i<512;i++) {
            float x=-.8F+(i%32)*.05F,y=-.4F+(i/32)*.05F;
            ByteBuffer target=ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());
            put(target,new float[]{x,y,-10,x+.01F,y,-10,x+.01F,y+.01F,-10,x,y+.01F,-10});
            target.flip();add(TerrainOccluderMesh.capture(target,28,4,NO_OCCLUDERS));
        }
    }
    private int frame(double yaw,double z) {
        Arrays.fill(model,0);double angle=Math.toRadians(yaw);
        model[0]=model[10]=(float)Math.cos(angle);model[2]=-(float)Math.sin(angle);
        model[8]=(float)Math.sin(angle);model[5]=model[15]=1;
        RenderMatrixBridge.capture(modelBuffer,projectionBuffer,viewport);
        container.viewZ=z;container.values.clear();container.values.addAll(original);
        int hidden=filter.filter(container,BlockRenderLayer.SOLID,RenderMatrixBridge.snapshot());
        sink+=hidden;return hidden;
    }
    private void measure(String name,int motion) {
        int min=512,max=0;
        for(int i=0;i<1200;i++)frame(yaw(motion,i),z(motion,i));
        long[] times=new long[2500];
        for(int i=0;i<times.length;i++) {
            long start=System.nanoTime();int hidden=frame(yaw(motion,i),z(motion,i));
            times[i]=System.nanoTime()-start;min=Math.min(min,hidden);max=Math.max(max,hidden);
        }
        Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT,"%s hidden=%d..%d/512 p50=%.3fms p95=%.3fms p99=%.3fms%n",
            name,min,max,times[1250]/1e6,times[2375]/1e6,times[2475]/1e6);
    }
    private static double yaw(int motion,int frame) {
        if(motion==1)return 20;
        return motion==3?20+2*Math.sin(frame*.02):0;
    }
    private static double z(int motion,int frame) {
        if(motion==2)return 0;
        return motion==4?.2*Math.sin(frame*.02):2;
    }
    private static void put(ByteBuffer data,float[] xyz) {
        for(int i=0;i<4;i++)data.putFloat(xyz[i*3]).putFloat(xyz[i*3+1]).putFloat(xyz[i*3+2])
            .putInt(-1).putFloat(i==1||i==2?.9F:.1F).putFloat(i>=2?.9F:.1F).putInt(0);
    }
    @SuppressWarnings("unchecked")
    private void add(TerrainOccluderMesh mesh) throws Exception {
        Field unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Unsafe unsafe=(Unsafe)unsafeField.get(null);
        TestChunk chunk=(TestChunk)unsafe.allocateInstance(TestChunk.class);chunk.compiled=new CompiledChunk();
        TerrainVertexSpace space=TerrainVertexSpace.capture(new BufferBuilder(28),chunk);
        Class<?> entry=Class.forName(CurrentFrameTerrainOcclusion.class.getName()+"$Entry");
        Constructor<?> constructor=entry.getDeclaredConstructor(RenderChunk.class,
            CompiledChunk.class,TerrainOccluderMesh.class,TerrainVertexSpace.class);constructor.setAccessible(true);
        Object value=constructor.newInstance(chunk,chunk.compiled,mesh,space);
        Field pending=CurrentFrameTerrainOcclusion.class.getDeclaredField("pending");pending.setAccessible(true);
        VertexBuffer vbo=(VertexBuffer)unsafe.allocateInstance(VertexBuffer.class);
        ((Map<VertexBuffer,Object>)pending.get(filter)).put(vbo,value);filter.uploaded(vbo);original.add(chunk);
    }
    private static final class TestChunk extends RenderChunk {
        private CompiledChunk compiled;
        private TestChunk(){super(null,null,0);}
        @Override public BlockPos getPosition(){return BlockPos.ORIGIN;}
        @Override public CompiledChunk getCompiledChunk(){return compiled;}
    }
    private static final class Container implements TerrainRenderListAccessor {
        final ArrayList<RenderChunk> values=new ArrayList<RenderChunk>();double viewZ;
        @Override public List<RenderChunk> ice$renderChunks(){return values;}
        @Override public boolean ice$initialized(){return true;}
        @Override public double ice$viewEntityX(){return 0;}
        @Override public double ice$viewEntityY(){return 0;}
        @Override public double ice$viewEntityZ(){return viewZ;}
    }
    public static void main(String[] args) throws Exception {
        SoftwareTerrainMovementBenchmark bench=new SoftwareTerrainMovementBenchmark();
        bench.measure("fixed-initial",0);
        bench.measure("fixed-yaw20",1);
        bench.measure("fixed-forward2",2);
        bench.measure("continuous-yaw18-to22",3);
        bench.measure("continuous-walk-near-wall",4);
        System.out.println("checksum="+sink+"; actual CurrentFrameTerrainOcclusion.filter, CPU only, no FPS claim");
    }
}
