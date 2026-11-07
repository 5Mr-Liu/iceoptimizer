package dev.rlcraft.ice.optimizer.render.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTerrainFaceIndexTest {
    @Test public void blockCopiesMatchScalarIndicesForEveryMaskAndPartialMaterialRange() {
        int quads = 17000;
        ByteBuffer raw = ByteBuffer.allocate(quads * 112).order(ByteOrder.nativeOrder());
        int[] classes = new int[quads];
        for (int q = 0; q < quads; q++) {
            quad(raw, q / 96 % 2 == 0, q % 7);
            if (q % 173 == 0) raw.putFloat(q * 112 + 2 * 28 + 8, 30);
            classes[q] = TerrainQuadClassifier.classify(raw, q * 112, 28);
        }
        raw.flip();
        NativeTerrainFaceIndex faces = new NativeTerrainFaceIndex(raw);
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            ByteBuffer actual = ByteBuffer.allocateDirect(131080).order(order);
            ByteBuffer expected = ByteBuffer.allocate(131080).order(order);
            for (int mask = 0; mask < 128; mask++) {
                for (int[] range : new int[][] {{0,65532,0},{12,1200,0},{65532,2468,65532},{65580,512,65532}}) {
                    actual.clear(); expected.clear(); actual.putInt(0x12345678); expected.putInt(0x12345678);
                    int before = actual.position();
                    int count = faces.writeShortIndices(actual, mask, range[0], range[1], range[2]);
                    for (int q = range[0]/4; q < (range[0]+range[1])/4; q++)
                        if ((mask & (1 << classes[q])) != 0)
                            for (int v = 0; v < 4; v++) expected.putShort((short)(q*4+v-range[2]));
                    assertEquals((actual.position()-before)/2,count);
                    actual.flip(); expected.flip();
                    assertEquals("mask="+mask+" first="+range[0]+" order="+order,expected,actual);
                }
            }
        }
    }

    @Test public void sequentialCopiesPreserveUnsignedRangeAndByteOrder() {
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            ByteBuffer actual = ByteBuffer.allocateDirect(131072).order(order);
            NativeTerrainFaceIndex.writeSequentialShortIndices(actual,65532);
            actual.flip();
            for (int i=0;i<65532;i++) assertEquals(i,actual.getShort() & 65535);
        }
    }
    @Test public void indexFilteringNeverReordersQuadsOrChangesProvokingVertex() {
        ByteBuffer raw=ByteBuffer.allocate(3*112).order(ByteOrder.nativeOrder());
        quad(raw,false,0); quad(raw,true,0); quad(raw,false,10); raw.flip();
        NativeTerrainFaceIndex faces=new NativeTerrainFaceIndex(raw);
        ByteBuffer indices=ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        assertEquals(8,write(faces,indices,faces.visibleMask(0,0,20),12));
        assertArrayEquals(new int[]{0,1,2,3,8,9,10,11},ints(indices));
        assertEquals(4,write(faces,indices,faces.visibleMask(0,0,-20),12));
        assertArrayEquals(new int[]{4,5,6,7},ints(indices));
        assertEquals(12,write(faces,indices,faces.allFaces(),12));
        assertArrayEquals(new int[]{0,1,2,3,4,5,6,7,8,9,10,11},ints(indices));
    }
    @Test public void coplanarCameraAndUnknownGeometryAreRetained() {
        ByteBuffer raw=ByteBuffer.allocate(2*112).order(ByteOrder.nativeOrder());
        quad(raw,false,0); quad(raw,true,0);raw.putFloat(112+2*28+8,2);raw.flip();
        NativeTerrainFaceIndex faces=new NativeTerrainFaceIndex(raw);
        ByteBuffer indices=ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        assertEquals(8,write(faces,indices,faces.visibleMask(0,0,0),8));
        assertEquals(4,write(faces,indices,faces.visibleMask(0,0,-10),8));
        assertArrayEquals(new int[]{4,5,6,7},ints(indices));
    }
    @Test public void shortPartsRebaseBeyondUnsignedShortLimitAndAppendWithoutOverwriting() {
        ByteBuffer raw=ByteBuffer.allocate(65540*28).order(ByteOrder.nativeOrder());
        for(int i=0;i<65540/4;i++)quad(raw,false,0);raw.flip();
        NativeTerrainFaceIndex faces=new NativeTerrainFaceIndex(raw);
        ByteBuffer indices=ByteBuffer.allocate(65540*2).order(ByteOrder.nativeOrder());
        assertEquals(65532,faces.writeShortIndices(indices,faces.allFaces(),0,65532));
        assertEquals(8,faces.writeShortIndices(indices,faces.allFaces(),65532,8));
        indices.flip();int[] values=ints(indices);
        assertEquals(65531,values[65531]);assertEquals(0,values[65532]);assertEquals(7,values[65539]);
    }
    @Test public void uploadTimePlaneTransformMatchesLocalCoordinatesIncludingMargins() {
        ByteBuffer raw=ByteBuffer.allocate(2*112).order(ByteOrder.nativeOrder());
        quad(raw,false,16);quad(raw,true,-8);raw.flip();
        NativeTerrainFaceIndex local=new NativeTerrainFaceIndex(raw);
        float scale=1.000001F, translation=8*scale-8;
        NativeTerrainFaceIndex transformed=new NativeTerrainFaceIndex(raw,scale,translation);
        for(double z:new double[]{-100,-8.01,-8,-7.99,0,15.99,16,16.01,100})
            assertEquals(local.visibleMask(0,0,z),transformed.visibleMask(0,0,z*scale+translation));
        for(double z:new double[]{-8-.001,-8+.001,16-.001,16+.001}) {
            double world=z*scale+translation;
            int original=local.visibleMask(0,0,(world-translation)/scale);
            assertEquals(original,original & transformed.visibleMask(0,0,world));
        }
    }
    private static int write(NativeTerrainFaceIndex f,ByteBuffer b,int mask,int count){b.clear();int n=f.writeShortIndices(b,mask,0,count);b.flip();return n;}
    private static int[] ints(ByteBuffer b){int[] a=new int[b.remaining()/2];for(int i=0;i<a.length;i++)a[i]=b.getShort()&65535;return a;}
    private static void quad(ByteBuffer b,boolean reverse,float z) {
        int[] order=reverse?new int[]{3,2,1,0}:new int[]{0,1,2,3};
        for(int c:order){b.putFloat(c==1||c==2?1:0).putFloat(c>=2?1:0).putFloat(z);for(int i=0;i<16;i++)b.put((byte)(c*17+i));}
    }
}
