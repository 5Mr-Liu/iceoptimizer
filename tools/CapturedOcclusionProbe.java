package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.benchmark.TerrainSceneData;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainVertexSpace;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import sun.misc.Unsafe;

/** Replays captured real uploaded occluders and the actual view through production code. */
public final class CapturedOcclusionProbe {
    public static void main(String[] args) throws Exception {
        TerrainSceneData scene=TerrainSceneData.read(args[0]);
        if(!"SOLID".equals(scene.layer)) throw new IllegalArgumentException("Requires solid scene");
        int warmups=Integer.getInteger("ice.occlusionWarmups",200);
        int samples=Integer.getInteger("ice.occlusionSamples",301);
        if(warmups<0||samples<1)throw new IllegalArgumentException("Invalid sampling counts");
        double angle=Math.toRadians(Double.parseDouble(System.getProperty("ice.occlusionYaw","0")));
        if(angle!=0)for(int row=0;row<4;row++) {
            float x=scene.model[row],z=scene.model[8+row];
            scene.model[row]=(float)(x*Math.cos(angle)-z*Math.sin(angle));
            scene.model[8+row]=(float)(x*Math.sin(angle)+z*Math.cos(angle));
        }
        Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);Unsafe unsafe=(Unsafe)uf.get(null);
        CurrentFrameTerrainOcclusion target=new CurrentFrameTerrainOcclusion();
        Constructor<TerrainOccluderMesh> meshCtor=TerrainOccluderMesh.class.getDeclaredConstructor(float[].class,double[].class);meshCtor.setAccessible(true);
        Constructor<TerrainVertexSpace> spaceCtor=TerrainVertexSpace.class.getDeclaredConstructor(int.class,int.class,int.class,boolean.class);spaceCtor.setAccessible(true);
        Class<?> entry=Class.forName(CurrentFrameTerrainOcclusion.class.getName()+"$Entry");
        Constructor<?> entryCtor=entry.getDeclaredConstructor(RenderChunk.class,CompiledChunk.class,TerrainOccluderMesh.class,TerrainVertexSpace.class);entryCtor.setAccessible(true);
        Field pending=CurrentFrameTerrainOcclusion.class.getDeclaredField("pending");pending.setAccessible(true);
        ArrayList<RenderChunk> original=new ArrayList<RenderChunk>(); int known=0,quads=0;long vertices=0;
        for(TerrainSceneData.Section s:scene.sections) {
            TestChunk chunk=(TestChunk)unsafe.allocateInstance(TestChunk.class);
            chunk.position=new BlockPos(s.position[0],s.position[1],s.position[2]);chunk.compiled=new CompiledChunk();original.add(chunk);vertices+=s.vertices;
            if(!s.known && s.owned && Boolean.getBoolean("ice.allBounds")) {
                ByteBuffer bytes=ByteBuffer.wrap(s.data).order(ByteOrder.nativeOrder());
                Arrays.fill(s.bounds,0,3,Double.POSITIVE_INFINITY);Arrays.fill(s.bounds,3,6,Double.NEGATIVE_INFINITY);
                for(int offset=0;offset<s.data.length;offset+=28)for(int axis=0;axis<3;axis++) {
                    float value=bytes.getFloat(offset+axis*4);s.bounds[axis]=Math.min(s.bounds[axis],value);s.bounds[axis+3]=Math.max(s.bounds[axis+3],value);
                }
                for(int axis=0;axis<3;axis++)s.origin[axis]=s.region[axis];
                s.quads=new float[0];s.known=true;s.renderRegions=false;
            }
            if(!s.known)continue;
            if(s.renderRegions) throw new IllegalStateException("Offline runtime is ordinary chunk coordinates");
            TerrainOccluderMesh mesh=meshCtor.newInstance(s.quads,s.bounds);
            TerrainVertexSpace space=spaceCtor.newInstance((int)s.origin[0],(int)s.origin[1],(int)s.origin[2],false);
            VertexBuffer vbo=(VertexBuffer)unsafe.allocateInstance(VertexBuffer.class);
            ((Map)pending.get(target)).put(vbo,entryCtor.newInstance(chunk,chunk.compiled,mesh,space));target.uploaded(vbo);
            known++;quads+=s.quads.length/12;
        }
        RenderMatrixBridge.capture(FloatBuffer.wrap(scene.model),FloatBuffer.wrap(scene.projection),IntBuffer.wrap(new int[]{0,0,1920,1080}));
        RenderMatrixBridge.Snapshot matrices=RenderMatrixBridge.snapshot(); Container container=new Container(scene.camera);
        Method filter=CurrentFrameTerrainOcclusion.class.getDeclaredMethod("filterLimited",Object.class,BlockRenderLayer.class,RenderMatrixBridge.Snapshot.class,boolean.class);filter.setAccessible(true);
        System.out.println("captured chunks="+original.size()+" known="+known+" quads="+quads+" vertices="+vertices);
        for(boolean priority:new boolean[]{true,false}) {
            long[] times=new long[samples];int removed=0; long before=target.getRasterizedQuads();
            for(int i=-warmups;i<times.length;i++) {
                container.values.clear();container.values.addAll(original);long start=System.nanoTime();
                removed=(Integer)filter.invoke(target,container,BlockRenderLayer.SOLID,matrices,priority);
                if(i>=0)times[i]=System.nanoTime()-start;
            }
            Arrays.sort(times);System.out.println("priority="+priority+" removed="+removed+" p50_ms="+times[samples/2]/1e6+" p95_ms="+times[Math.min(samples-1,(int)(samples*.95))]/1e6+" rasterized_per_call="+(target.getRasterizedQuads()-before)/(double)(warmups+samples));
            long orderHash=1L;for(RenderChunk chunk:container.values)orderHash=31L*orderHash+original.indexOf(chunk);
            System.out.println("remaining_order_hash="+orderHash+" remaining_count="+container.values.size());
            if(Boolean.getBoolean("ice.occlusionDumpIndices")) {
                StringBuilder indices=new StringBuilder();for(RenderChunk chunk:container.values)indices.append(original.indexOf(chunk)).append(',');
                System.out.println("remaining_indices="+indices);
            }
            for(String counter:new String[]{"getReadOnlyTailPasses","getReadOnlyTailBudgetRejects","getReadOnlyTailBudgetStops"})try {
                System.out.println(counter+"="+target.getClass().getMethod(counter).invoke(target));
            } catch(NoSuchMethodException olderBaseline) { }
        }
    }
    private static final class TestChunk extends RenderChunk {
        BlockPos position;CompiledChunk compiled;
        private TestChunk(){super(null,null,0);}
        @Override public BlockPos getPosition(){return position;}
        @Override public CompiledChunk getCompiledChunk(){return compiled;}
    }
    private static final class Container implements TerrainRenderListAccessor {
        final ArrayList<RenderChunk> values=new ArrayList<RenderChunk>(); final double[] camera;
        Container(double[] camera){this.camera=camera;}
        @Override public List<RenderChunk> ice$renderChunks(){return values;}
        @Override public boolean ice$initialized(){return true;}
        @Override public double ice$viewEntityX(){return camera[0];}
        @Override public double ice$viewEntityY(){return camera[1];}
        @Override public double ice$viewEntityZ(){return camera[2];}
    }
}
