package dev.rlcraft.ice.benchmark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Lossless position-format feasibility on captured real meshes; no FPS claim. */
public final class TerrainCompactFormatProbe {
    public static void main(String[] arguments) throws Exception {
        TerrainSceneData scene = TerrainSceneData.read(arguments[0]);
        long total=0, eligible=0, vertices=0, integralVertices=0;
        int meshes=0, integralMeshes=0;
        for (TerrainSceneData.Section section : scene.sections) {
            if (!section.owned) continue;
            meshes++; total+=section.data.length; vertices+=section.vertices;
            ByteBuffer source=ByteBuffer.wrap(section.data).order(ByteOrder.LITTLE_ENDIAN);
            if(meshes==1) {
                System.out.println("first_region="+java.util.Arrays.toString(section.region)+" section="+java.util.Arrays.toString(section.position));
                for(int vertex=0;vertex<Math.min(4,section.vertices);vertex++)
                    System.out.println("position="+source.getFloat(vertex*28)+","+source.getFloat(vertex*28+4)+","+source.getFloat(vertex*28+8));
            }
            boolean all=true;
            for(int vertex=0;vertex<section.vertices;vertex++) {
                boolean exact=true;
                for(int component=0;component<3;component++) {
                    float value=source.getFloat(vertex*28+component*4);
                    exact &= Float.floatToRawIntBits(value)==Float.floatToRawIntBits((float)(short)value);
                }
                if(exact) integralVertices++; else all=false;
            }
            if(all){integralMeshes++;eligible+=section.data.length;}
        }
        System.out.println("layer="+scene.layer+" owned_meshes="+meshes+" integral_meshes="+integralMeshes);
        System.out.println("vertices="+vertices+" integral_vertices="+integralVertices);
        System.out.println("original_bytes="+total+" eligible_bytes="+eligible+" packed24_bytes="+(total-eligible/7));
        System.out.println("lossless_reduction_percent="+(100.0*eligible/7/total));
    }
}
