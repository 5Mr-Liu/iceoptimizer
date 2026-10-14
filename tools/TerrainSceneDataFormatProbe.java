package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;

public final class TerrainSceneDataFormatProbe {
    public static void main(String[] args)throws Exception {
        Path folder=Paths.get(args[0]);Files.createDirectories(folder);
        byte[] expanded=new byte[64*28];for(int v=0;v<64;v++)for(int b=0;b<28;b++)expanded[v*28+b]=(byte)(v%4*31+b);
        for(boolean v2:new boolean[]{false,true}) {
            Path file=folder.resolve(v2?"indexed-scene-v2.bin":"plain-scene-v1.bin");write(file,v2,false,expanded);
            TerrainSceneData scene=TerrainSceneData.read(file.toString());
            if(scene.virtualReplayOffsets!=v2)throw new AssertionError("Offset format flag");
            for(TerrainSceneData.Section s:scene.sections)if(!Arrays.equals(expanded,s.data))throw new AssertionError("Expanded bytes");
            if(scene.sections[1].offset!=64*28||scene.sections[1].nativeOffset!=(v2?336:64*28))throw new AssertionError("Physical/replay offsets");
        }
        Path invalid=folder.resolve("invalid-index-v2.bin");write(invalid,true,true,expanded);
        try {TerrainSceneData.read(invalid.toString());throw new AssertionError("Invalid index accepted");}
        catch(IOException expected) { }
        System.out.println("scene_format=true v1_preserved=true v2_exact_expansion=true virtual_offsets_nonoverlapping=true invalid_index_rejected=true");
    }
    static void write(Path path,boolean v2,boolean invalid,byte[] expanded)throws Exception {
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path))) {
            out.writeUTF(v2?"ICE_TERRAIN_SCENE_2":"ICE_TERRAIN_SCENE_1");out.writeUTF("CUTOUT_MIPPED");
            for(int i=0;i<3;i++)out.writeDouble(0);for(int i=0;i<32;i++)out.writeFloat(i%5==0?1:0);out.writeInt(2);
            for(int section=0;section<2;section++) {
                for(int i=0;i<3;i++)out.writeInt(0);out.writeBoolean(true);for(int i=0;i<3;i++)out.writeInt(0);
                out.writeInt(64);out.writeInt(1);out.writeLong(section*(v2?336:64*28));
                if(v2) {
                    out.writeBoolean(true);out.writeInt(4);out.write(expanded,0,4*28);
                    ByteBuffer indices=ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
                    for(int i=0;i<64;i++)indices.putShort((short)(invalid?4:i%4));out.write(indices.array());
                } else out.write(expanded);
                out.writeBoolean(false);
            }
        }
    }
}
