package dev.rlcraft.ice.optimizer.render.terrain;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainFrameDataTest {
    @Test public void preservesOpaqueOrderReversesTransparencyAndAddressesGlobalOffsets() {
        NativeTerrainFrameData data = new NativeTerrainFrameData();
        data.add(0, 8, 100, 123456, 1, 2, 3, 1);
        data.add(3, 12, 200, 4, 5, 6, 1);
        data.add(1, 16, 300, 7, 8, 9, 1);
        data.add(3, 20, 400, 10, 11, 12, 1);
        int[] words = new int[data.size() * 9]; data.pack(words);
        assertArrayEquals(new int[]{0, 1, 2, 2}, data.starts);
        assertArrayEquals(new int[]{8,1,100,123456,0,16,1,300,0,1,20,1,400,0,2,12,1,200,0,3},
            java.util.Arrays.copyOfRange(words,16,36));
        assertEquals(10, Float.intBitsToFloat(words[8]), 0);
        assertEquals(4, Float.intBitsToFloat(words[12]), 0);
        data.clear(); data.add(2,4,40,-10,0,10,1.000001f); data.pack(words);
        assertEquals(1,data.size()); assertEquals(4,words[4]); assertEquals(40,words[6]); assertEquals(0,words[7]);
    }

    @Test public void growsForDenseViewsWithoutDroppingDraws() {
        NativeTerrainFrameData data = new NativeTerrainFrameData();
        for(int i=0;i<20000;i++) data.add(i%4,4,i*4,i,0,0,1);
        int[] words = new int[data.size()*9]; data.pack(words);
        assertEquals(20000,data.size());
        for(int layer=0;layer<4;layer++) for(int i=0;i<5000;i++) {
            int source = (layer==3?4999-i:i)*4+layer;
            int draw=layer*5000+i;
            assertEquals(source*4,words[80000+draw*5+2]);
            assertEquals(draw,words[80000+draw*5+4]);
            assertEquals(source,Float.intBitsToFloat(words[draw*4]),0);
        }
    }

    @Test public void transparentPartsKeepQuadOrderWhenChunksReverse() {
        NativeTerrainFrameData data = new NativeTerrainFrameData();
        data.add(3, 100, 65532, 66532, 1, 0, 0, 1);
        data.add(3, 65532, 0, 1000, 1, 0, 0, 1);
        data.add(3, 20, 131164, 145532, 2, 0, 0, 1);
        data.add(3, 65532, 65632, 80000, 2, 0, 0, 1);
        int[] words = new int[36]; data.pack(words);
        assertArrayEquals(new int[]{65532,1,65632,80000,0,20,1,131164,145532,1,
            65532,1,0,1000,2,100,1,65532,66532,3}, java.util.Arrays.copyOfRange(words,16,36));
    }
}
