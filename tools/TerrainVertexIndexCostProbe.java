package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.benchmark.TerrainSceneData;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;

/** CPU upload preparation on captured meshes; deliberately excludes GPU/FPS claims. */
public final class TerrainVertexIndexCostProbe {
    static volatile long sink;
    public static void main(String[] args)throws Exception {
        TerrainSceneData scene=TerrainSceneData.read(args[0]);List<ByteBuffer> sources=new ArrayList<ByteBuffer>();int max=0;
        for(TerrainSceneData.Section s:scene.sections)if(s.owned&&s.data.length>0) {
            ByteBuffer input=BufferUtils.createByteBuffer(s.data.length).order(ByteOrder.nativeOrder());input.put(s.data).flip();sources.add(input);max=Math.max(max,s.data.length);
        }
        ByteBuffer scratch=BufferUtils.createByteBuffer(max).order(ByteOrder.nativeOrder());
        TerrainVertexIndexBuilder builder=new TerrainVertexIndexBuilder(new CacheBudget(16L<<20,16L<<20,1));
        double[][] samples=new double[3][15];
        for(int iteration=-8;iteration<15;iteration++)for(int visit=0;visit<3;visit++) {
            int mode=Math.floorMod(iteration+visit,3);long start=System.nanoTime(),bytes=0;
            for(ByteBuffer source:sources) {
                TerrainVertexIndexBuilder.Result encoded=mode==0?null:builder.encode(source);
                if(mode==1){bytes+=encoded==null?source.limit():encoded.allocationBytes;continue;}
                if(encoded!=null){LwjglTerrainArena.normalizePositions(encoded.bytes,encoded.indexOffset,0,0,0,1.000001F,.00000763F,0,0,0);bytes+=encoded.allocationBytes;}
                else {scratch.clear();scratch.put(source.duplicate());scratch.flip();LwjglTerrainArena.normalizePositions(scratch,source.limit(),0,0,0,1.000001F,.00000763F,0,0,0);bytes+=source.limit();}
            }
            long elapsed=System.nanoTime()-start;sink+=bytes;
            if(iteration>=0)samples[mode][iteration]=elapsed/1e6;
        }
        for(int mode=0;mode<3;mode++){Arrays.sort(samples[mode]);System.out.println("mode="+new String[]{"plain_copy_normalize","index_encode_only","index_encode_normalize"}[mode]+" median_ms="+samples[mode][7]+" meshes="+sources.size());}
        builder.close();System.out.println("cpu_upload_preparation_only=true full_frame_benefit_unproven=true sink="+sink);
    }
}
