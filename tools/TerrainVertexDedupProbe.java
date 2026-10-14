package dev.rlcraft.ice.benchmark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Raw-bit vertex indexing statistics and reconstruction, not a draw/FPS test. */
public final class TerrainVertexDedupProbe {
    public static final class Indexed {
        public final byte[] vertices;
        public final int[] indices;
        public final int unique, indexBytes;
        Indexed(byte[] vertices,int[] indices,int unique) {
            this.vertices=vertices;this.indices=indices;this.unique=unique;
            indexBytes=unique<=65536?2:4;
        }
    }
    public static Indexed encode(byte[] source) {
        if(source.length%28!=0)throw new IllegalArgumentException("vertex stride");
        ByteBuffer input=ByteBuffer.wrap(source).order(ByteOrder.nativeOrder());
        int count=source.length/28,capacity=1;
        while(capacity<count*2)capacity<<=1;
        int[] table=new int[capacity],ordinal=new int[count],indices=new int[count];
        byte[] output=new byte[source.length];int unique=0;
        for(int vertex=0;vertex<count;vertex++) {
            int offset=vertex*28,hash=0x811C9DC5;
            for(int word=0;word<7;word++)hash=(hash^input.getInt(offset+word*4))*0x01000193;
            hash^=hash>>>16;int slot=hash&(capacity-1);
            while(table[slot]!=0&&!equal(input,offset,(table[slot]-1)*28))slot=(slot+1)&(capacity-1);
            if(table[slot]==0) {
                table[slot]=vertex+1;ordinal[vertex]=unique;
                System.arraycopy(source,offset,output,unique++*28,28);
            }
            indices[vertex]=ordinal[table[slot]-1];
        }
        return new Indexed(java.util.Arrays.copyOf(output,unique*28),indices,unique);
    }
    private static boolean equal(ByteBuffer input,int a,int b) {
        for(int word=0;word<7;word++)if(input.getInt(a+word*4)!=input.getInt(b+word*4))return false;
        return true;
    }
    public static void main(String[] args)throws Exception {
        TerrainSceneData scene=TerrainSceneData.read(args[0]);
        long original=0,indexed=0,selected=0,vertices=0,unique=0;int meshes=0,beneficial=0;
        for(TerrainSceneData.Section section:scene.sections)if(section.owned) {
            Indexed encoded=encode(section.data);
            for(int i=0;i<encoded.indices.length;i++)for(int b=0;b<28;b++)
                if(section.data[i*28+b]!=encoded.vertices[encoded.indices[i]*28+b])
                    throw new AssertionError("Vertex reconstruction changed a raw byte");
            long bytes=encoded.vertices.length+(long)encoded.indices.length*encoded.indexBytes;
            original+=section.data.length;indexed+=bytes;selected+=Math.min(bytes,section.data.length);
            vertices+=encoded.indices.length;unique+=encoded.unique;meshes++;
            if(bytes<section.data.length)beneficial++;
        }
        System.out.println("layer="+scene.layer+" owned_meshes="+meshes+" beneficial_meshes="+beneficial);
        System.out.println("vertices="+vertices+" unique_vertices="+unique+" original_bytes="+original
            +" indexed_bytes="+indexed+" smaller_format_bytes="+selected);
        System.out.println("exact_raw_vertex_reconstruction=true quad_order_preserved=true scope=CPU_format_only_no_GPU_or_FPS_claim");
    }
}
