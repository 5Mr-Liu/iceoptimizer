package dev.rlcraft.ice.optimizer.render.visibility;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/** Standalone CPU algorithm comparison; optional counters exist only in generated offline copies. */
public final class SoftwareOcclusionAlgorithmBenchmark {
    private static volatile long sink;
    private static final float[] IDENTITY = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final boolean COUNTERS = Boolean.getBoolean("offline.counters");

    public static void main(String[] args) throws Exception {
        capture("capture-separated-512", separated());
        raster("raster-one-wall", walls(1), false);
        raster("raster-eight-overlap", walls(8), false);
        raster("raster-moving-overlap", walls(8), true);
        raster("raster-tiny-faces", tiny(), true);
        System.out.println("sink=" + sink);
    }

    private static void capture(String name, final ByteBuffer source) throws Exception {
        measure(name, () -> sink += TerrainOccluderMesh.capture(source, 28, source.limit()/28,
            (a,b,c,d) -> true).quads.length);
    }

    private static void raster(String name, final float[][] faces, final boolean moving) throws Exception {
        final SoftwareOcclusionRaster raster = new SoftwareOcclusionRaster(160,90);
        final SoftwareOcclusionRaster.Bounds bounds = new SoftwareOcclusionRaster.Bounds();
        final int[] frame = {0};
        measure(name, () -> {
            double shift = moving ? ((frame[0]++ & 63) - 32) * .002 : 0;
            raster.begin(IDENTITY, IDENTITY, shift, shift * .3, 0, 120000);
            for (float[] face : faces) raster.rasterize(face,0,0,0,0);
            if (raster.projectBounds(-.5,-.5,.8,.5,.5,.8,bounds))
                sink += raster.occluded(bounds) ? 1 : 0;
        });
    }

    private static void measure(String name, Task task) throws Exception {
        if (COUNTERS) {
            clearCounter(SoftwareOcclusionRaster.class, "offlineDepthWrites");
            clearCounter(TerrainOccluderMesh.class, "offlineSorts");
            task.run();
            System.out.println(name + " depthWrites=" + counter(SoftwareOcclusionRaster.class,"offlineDepthWrites")
                + " sorts=" + counter(TerrainOccluderMesh.class,"offlineSorts"));
            return;
        }
        for (int i=0;i<4000;i++) task.run();
        long[] times = new long[6000];
        for (int i=0;i<times.length;i++) { long start=System.nanoTime();task.run();times[i]=System.nanoTime()-start; }
        Arrays.sort(times);
        System.out.printf(Locale.ROOT,"%s p50=%.3fus p95=%.3fus%n",name,times[3000]/1e3,times[5700]/1e3);
    }
    private static void clearCounter(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);field.setLong(null,0L);
    }
    private static long counter(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);return field.getLong(null);
    }
    private interface Task { void run(); }
    private static float[] quad(float x0,float y0,float x1,float y1,float z) {
        return new float[] {x0,y0,z,x1,y0,z,x1,y1,z,x0,y1,z};
    }
    private static float[][] walls(int count) {
        float[][] result = new float[count][];
        for(int i=0;i<count;i++) result[i]=quad(-1.1F,-1.1F,1.1F,1.1F,-.8F+i*.1F);
        return result;
    }
    private static float[][] tiny() {
        float[][] result=new float[512][];
        for(int i=0;i<result.length;i++) {
            float x=-.9F+(i%32)*.05F,y=-.8F+(i/32)*.08F;
            result[i]=quad(x,y,x+.004F,y+.004F,-.5F);
        }
        return result;
    }
    private static ByteBuffer separated() {
        ByteBuffer out=ByteBuffer.allocate(512*112).order(ByteOrder.nativeOrder());
        for(int i=0;i<512;i++) {
            float x=(i%32)*2,y=(i/32)*2;
            for(int v=0;v<4;v++) out.putFloat(x+(v==1||v==2?1:0)).putFloat(y+(v>=2?1:0))
                .putFloat((i*17)%7).putInt(-1).putFloat(v==1||v==2?.9F:.1F)
                .putFloat(v>=2?.9F:.1F).putInt(0);
        }
        out.flip();return out;
    }
}
