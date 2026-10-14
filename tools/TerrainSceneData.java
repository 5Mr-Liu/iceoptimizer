package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Reader shared by offline replay probes; format is owned by TerrainSceneCapture. */
public final class TerrainSceneData {
    public String layer;
    public final double[] camera = new double[3];
    public final float[] model = new float[16], projection = new float[16];
    public Section[] sections;
    /** Decoding compact storage requires virtual, nonoverlapping replay offsets. */
    public boolean virtualReplayOffsets;
    public static final class Section {
        public final int[] position = new int[3], region = new int[3];
        public boolean owned, known, renderRegions, indexed;
        public int vertices, buffer, storedVertices, storageBytes;
        public long offset, nativeOffset;
        public byte[] data;
        public final double[] origin = new double[3], bounds = new double[6];
        public float[] quads;
    }
    public static TerrainSceneData read(String path) throws IOException {
        TerrainSceneData scene = new TerrainSceneData();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)))) {
            String format=in.readUTF();boolean v2="ICE_TERRAIN_SCENE_2".equals(format);
            if (!v2&&!"ICE_TERRAIN_SCENE_1".equals(format)) throw new IOException("Unknown scene format");
            scene.layer = in.readUTF();
            for (int i=0;i<3;i++) scene.camera[i]=in.readDouble();
            for (float[] matrix : new float[][]{scene.model,scene.projection}) for(int i=0;i<16;i++) matrix[i]=in.readFloat();
            int count=in.readInt(); if(count<0||count>131072) throw new IOException("Scene count");
            scene.sections=new Section[count];
            for(int n=0;n<count;n++) {
                Section s=scene.sections[n]=new Section();
                for(int i=0;i<3;i++) s.position[i]=in.readInt();
                s.owned=in.readBoolean();
                if(s.owned) {
                    for(int i=0;i<3;i++) s.region[i]=in.readInt();
                    s.vertices=in.readInt(); s.buffer=in.readInt(); s.offset=in.readLong();
                    s.nativeOffset=s.offset;
                    if(s.vertices<0||s.vertices>600000) throw new IOException("Vertex count");
                    s.indexed=v2&&in.readBoolean();s.storedVertices=v2?in.readInt():s.vertices;
                    if(s.storedVertices<0||s.storedVertices>s.vertices
                        ||s.indexed&&s.storedVertices>65536||!s.indexed&&s.storedVertices!=s.vertices)
                        throw new IOException("Stored vertex count");
                    s.storageBytes=Math.addExact(Math.multiplyExact(s.storedVertices,28),s.indexed?s.vertices*2:0);
                    byte[] payload=new byte[s.storageBytes];in.readFully(payload);
                    if(!s.indexed)s.data=payload;
                    else {
                        scene.virtualReplayOffsets=true;
                        s.data=new byte[s.vertices*28];ByteBuffer encoded=ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder());
                        for(int vertex=0;vertex<s.vertices;vertex++) {
                            int selected=encoded.getShort(s.storedVertices*28+vertex*2)&65535;
                            if(selected>=s.storedVertices)throw new IOException("Index out of bounds");
                            System.arraycopy(payload,selected*28,s.data,vertex*28,28);
                        }
                    }
                }
                s.known=in.readBoolean();
                if(s.known) {
                    s.renderRegions=in.readBoolean();
                    for(int i=0;i<3;i++) s.origin[i]=in.readDouble();
                    for(int i=0;i<6;i++) s.bounds[i]=in.readDouble();
                    int length=in.readInt(); if(length<0||length>6144||length%12!=0) throw new IOException("Occluder count");
                    s.quads=new float[length]; for(int i=0;i<length;i++) s.quads[i]=in.readFloat();
                }
            }
            if(in.read()!=-1) throw new IOException("Trailing scene data");
        }
        if(scene.virtualReplayOffsets) {
            Map<Integer,Long> next=new HashMap<Integer,Long>();
            for(Section s:scene.sections)if(s.owned) {
                Long start=next.get(s.buffer);s.offset=start==null?0:start;
                next.put(s.buffer,s.offset+s.data.length);
            }
            System.out.println("scene_indexed_storage=true replay_offsets=virtual original_offsets=nativeOffset");
        }
        return scene;
    }
}
