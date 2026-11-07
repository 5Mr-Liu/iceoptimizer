package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.*;
import java.util.*;

/** CPU-only preparation workload; run unchanged against baseline/candidate classes. */
public final class NativeTerrainPreparationBenchmark {
    private static volatile long sink;
    public static void main(String[] args) {
        final int quads = 16383, meshes = 64, samples = 400;
        for (String layout : new String[] {"clustered", "interleaved"}) {
            ByteBuffer raw = ByteBuffer.allocateDirect(quads * 112).order(ByteOrder.nativeOrder());
            for (int q=0;q<quads;q++) {
                boolean reverse = (layout.equals("clustered") ? q/256 : q) % 2 == 0;
                for(int c=0;c<4;c++) {
                    int corner=reverse?3-c:c;
                    raw.putFloat(corner==1||corner==2?1:0).putFloat(corner>=2?1:0).putFloat(0);
                    raw.position(raw.position()+16);
                }
            }
            raw.flip(); NativeTerrainFaceIndex faces=new NativeTerrainFaceIndex(raw);
            ByteBuffer target=ByteBuffer.allocateDirect(quads*8).order(ByteOrder.nativeOrder());
            for(String mode:new String[]{"all","half","none","sequential"}) {
                int mask=mode.equals("all")?faces.allFaces():mode.equals("half")?faces.visibleMask(0,0,20):1;
                long[] times=new long[samples];long bytes=0;
                for(int frame=-150;frame<samples;frame++) {
                    long start=System.nanoTime();
                    for(int mesh=0;mesh<meshes;mesh++) {
                        target.clear();
                        if(mode.equals("sequential"))NativeTerrainFaceIndex.writeSequentialShortIndices(target,quads*4);
                        else faces.writeShortIndices(target,mask,0,quads*4);
                        bytes+=target.position();
                        if(target.position()>0)sink+=target.getShort(target.position()-2)&65535;
                    }
                    if(frame>=0)times[frame]=System.nanoTime()-start;
                }
                Arrays.sort(times);
                System.out.printf(Locale.ROOT,"layout=%s mode=%s meshes=%d vertices=%d p50_ms=%.4f p95_ms=%.4f p99_ms=%.4f max_ms=%.4f bytes=%d checksum=%d%n",
                    layout,mode,meshes,quads*4*meshes,times[samples/2]/1e6,times[samples*95/100]/1e6,times[samples*99/100]/1e6,times[samples-1]/1e6,bytes,sink);
            }
        }
    }
}
