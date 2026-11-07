package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

public class NativeTerrainMaterialsTest {
    @Test public void opaqueRangesFollowG5FirstMaterialOrderAndDeferOverlay() {
        Object a=new Object(),b=new Object(),grass=new Object();
        NativeTerrainMaterials m=NativeTerrainMaterials.ranges(new Object[]{a,b,grass,a,a,null,b,grass},8,false,grass);
        assertArrayEquals(new int[]{0,12,4,24,8,28},m.first);
        assertArrayEquals(new int[]{4,8,4,4,4,4},m.count);
    }
    @Test public void transparentRangesKeepInterleavingAndSnapshotSurvivesBuilderReuse() {
        Object a=new Object(),b=new Object();Object[] input={a,a,b,a,b,b};
        NativeTerrainMaterials m=NativeTerrainMaterials.ranges(input,6,true,null);
        java.util.Arrays.fill(input,null);
        assertArrayEquals(new int[]{0,8,12,16},m.first);
        assertArrayEquals(new int[]{8,4,4,8},m.count);
    }
    @Test public void materialIndicesCrossPagesWithLocalRebasingAndStableOrder() throws Exception {
        Object a=new Object(),b=new Object();Object[] quads=new Object[16386];
        java.util.Arrays.fill(quads,a);quads[16382]=b;quads[16383]=b;
        NativeTerrainMaterials materials=NativeTerrainMaterials.ranges(quads,quads.length,true,null);
        Field tex=NativeTerrainMaterials.class.getDeclaredField("textures");tex.setAccessible(true);tex.set(materials,new int[]{11,22,11});
        NativeTerrainAllocator.Range[] ranges={new NativeTerrainAllocator.Range(400,65532),new NativeTerrainAllocator.Range(NativeTerrainPages21.PAGE_VERTICES+12,12)};
        NativeTerrainAllocation allocation=new NativeTerrainAllocation(ranges,65544);
        Constructor<TerrainVertexSpace> ctor=TerrainVertexSpace.class.getDeclaredConstructor(int.class,int.class,int.class,boolean.class);ctor.setAccessible(true);
        NativeTerrainRenderer.Mesh mesh=new NativeTerrainRenderer.Mesh(new NativeTerrainRenderer.Section(null),3,allocation,65544,ctor.newInstance(0,0,0,false),null,materials);
        Field unsafe=Unsafe.class.getDeclaredField("theUnsafe");unsafe.setAccessible(true);
        NativeTerrainRenderer owner=(NativeTerrainRenderer)((Unsafe)unsafe.get(null)).allocateInstance(NativeTerrainRenderer.class);
        set(owner,"indexUpload",BufferUtils.createByteBuffer(65544*2));set(owner,"indexCopies",new int[768]);
        Method update=NativeTerrainRenderer.class.getDeclaredMethod("updateIndices",NativeTerrainRenderer.Mesh.class,int.class);update.setAccessible(true);update.invoke(owner,mesh,0);
        assertEquals(4,mesh.materialDrawCount);
        assertArrayEquals(new int[]{65528,400,400,11,4,65928,400,22,4,131076,131076,22,8,131080,131076,11},java.util.Arrays.copyOf(mesh.materialDraws,16));
        ByteBuffer bytes=(ByteBuffer)get(owner,"indexUpload");
        assertEquals(65528,bytes.getShort(65528*2)&65535);assertEquals(0,bytes.getShort(65532*2)&65535);
        assertEquals(4,bytes.getShort(65536*2)&65535);
        NativeTerrainFrameData frame=new NativeTerrainFrameData();
        for(int i=mesh.materialDrawCount-1;i>=0;i--){int d=i*4;frame.add(3,mesh.materialDraws[d],mesh.materialDraws[d+1],mesh.materialDraws[d+2],0,0,0,1,mesh.materialDraws[d+3]);}
        int[] words=new int[36];frame.pack(words);assertArrayEquals(new int[]{11,22,22,11},java.util.Arrays.copyOf(frame.materials(),4));
        for(int i=0;i<4;i++)assertEquals(mesh.materialDraws[i*4+1],words[16+i*5+2]);
    }
    private static void set(Object o,String n,Object v)throws Exception {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    private static Object get(Object o,String n)throws Exception {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
}
