package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

/** Production allocator with a GL driver that has no readback/copy API. */
public class NativeTerrainPages21Test {
    private static final int PAGE = NativeTerrainPages21.PAGE_VERTICES;

    @Test public void addingPagesKeepsOldBuffersAndUploadsOnlyNewPayload() throws Exception {
        Fixture f = new Fixture();
        Object a = f.upload(4), b = f.upload(PAGE);
        int address = f.first(a), buffer = f.buffer(address);
        byte[] original = Driver.data.get(buffer).clone();
        long uploaded = Driver.uploaded;
        Object c = f.upload(PAGE);
        assertEquals(buffer, f.buffer(address));
        assertArrayEquals(original, Driver.data.get(buffer));
        assertEquals(PAGE * 28L, Driver.uploaded - uploaded);
        assertEquals(3, f.pages());
        f.free(a); f.free(b); f.free(c);
        assertEquals(0, f.pages()); assertTrue(Driver.data.isEmpty());
    }

    @Test public void adjacentHolesCoalesceAndEmptyPagesAreDeletedAndReused() throws Exception {
        Fixture f = new Fixture();
        Object a = f.upload(20000), b = f.upload(20000), c = f.upload(20000);
        int address = f.first(a), buffer = f.buffer(address);
        f.free(a); f.free(b);
        Object joined = f.upload(40000);
        assertEquals(address, f.first(joined)); assertEquals(buffer, f.buffer(address));
        assertEquals(1, f.pages());
        f.free(joined); f.free(c); assertEquals(0, f.pages());
        Object reused = f.upload(4);
        assertEquals(address, f.first(reused)); assertNotEquals(buffer, f.buffer(address));
        f.free(reused);
    }

    @Test public void failedMultipartUploadAndIndexAllocationRetainResidentGeometry() throws Exception {
        for (int failure : new int[] {1, 2}) {
            Fixture f = new Fixture(); Object old = f.upload(4);
            int address = f.first(old), buffer = f.buffer(address);
            byte[] resident = Driver.data.get(buffer).clone();
            if (failure == 1) Driver.failAllocation = Driver.allocations + 2;
            else Driver.failUpload = Driver.uploads + 3;
            try { f.upload(PAGE + 4); fail("injected failure must propagate"); }
            catch (IllegalStateException expected) { }
            assertEquals(1, f.pages()); assertEquals(2, Driver.data.size());
            assertEquals(buffer, f.buffer(address));
            assertArrayEquals(Arrays.copyOf(resident, 112), Arrays.copyOf(Driver.data.get(buffer), 112));
            assertEquals(101, Driver.binding);
            Driver.failAllocation = Driver.failUpload = 0;
            f.free(old); assertTrue(Driver.data.isEmpty());
        }
    }

    @Test public void indexUpdatesRespectPageOffsetsAndRestoreSourceAndBindings() throws Exception {
        Fixture f = new Fixture(); f.upload(4); Object mesh = f.upload(PAGE);
        ByteBuffer source = BufferUtils.createByteBuffer(32);
        for(int i=0;i<32;i++)source.put((byte)(i+1)); source.flip(); source.position(2);
        int first = f.first(mesh), second = f.part(mesh,1);
        f.invoke("uploadIndices", new Class<?>[]{ByteBuffer.class,int[].class,int.class},
            source,new int[]{4,first,8,20,second,12},2);
        assertEquals(5, Driver.data.get(f.indexBuffer(first))[NativeTerrainPages21.local(first)*2]);
        assertEquals(21, Driver.data.get(f.indexBuffer(second))[NativeTerrainPages21.local(second)*2]);
        assertEquals(2, source.position()); assertEquals(32, source.limit()); assertEquals(101,Driver.binding);
        f.invoke("delete", new Class<?>[0]);
    }

    @Test public void repeatedLoadUnloadHasBoundedLiveMemoryAndStableResidentAddresses() throws Exception {
        Fixture f = new Fixture(); Object anchor = f.upload(4);
        int first = f.first(anchor), buffer = f.buffer(first);
        for(int cycle=0;cycle<30;cycle++) {
            List<Object> meshes = new ArrayList<>();
            for(int n=0;n<40;n++) meshes.add(f.upload(4000));
            assertEquals(2,f.pages());
            Collections.shuffle(meshes,new Random(cycle));
            for(Object mesh:meshes)f.free(mesh);
            assertEquals(1,f.pages()); assertEquals(buffer,f.buffer(first));
        }
        assertEquals(2L*PAGE*30,Driver.peakBytes);
        f.free(anchor); assertEquals(0L,Driver.liveBytes());
    }

    static final class Fixture extends ClassLoader {
        final Class<?> type, allocation; final Object storage;
        Fixture() throws Exception {
            super(NativeTerrainPages21Test.class.getClassLoader()); Driver.reset();
            // One loader preserves package access, including the allocator comparator.
            define("NativeTerrainAllocator$Range"); define("NativeTerrainAllocator$1");
            define("NativeTerrainAllocator"); allocation=define("NativeTerrainAllocation");
            define("NativeTerrainPages21$Page"); type=define("NativeTerrainPages21");
            Constructor<?> ctor=type.getDeclaredConstructor(int.class); ctor.setAccessible(true); storage=ctor.newInstance(28);
        }
        Class<?> define(String simple) throws Exception {
            String name="dev.rlcraft.ice.optimizer.render.terrain."+simple;
            ClassReader r=new ClassReader(name); ClassWriter w=new ClassWriter(0);
            r.accept(new ClassVisitor(Opcodes.ASM5,w) {
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    return new MethodVisitor(Opcodes.ASM5,super.visitMethod(a,n,d,s,e)) {
                        @Override public void visitMethodInsn(int op,String owner,String n,String d,boolean itf) {
                            if(owner.startsWith("org/lwjgl/opengl/")) owner=org.objectweb.asm.Type.getInternalName(Driver.class);
                            super.visitMethodInsn(op,owner,n,d,itf);
                        }
                    };
                }
            },0);
            byte[] bytes=w.toByteArray(); return defineClass(name,bytes,0,bytes.length);
        }
        Object upload(int count) throws Exception {
            ByteBuffer source=BufferUtils.createByteBuffer(count*28+4); source.position(4); source.putInt(0x12345678); source.position(4);
            try { return invoke("upload",new Class<?>[]{ByteBuffer.class,int.class},source,count); }
            finally { assertEquals(4,source.position()); assertEquals(count*28+4,source.limit()); }
        }
        int first(Object mesh) throws Exception { return part(mesh,0); }
        int part(Object mesh,int part) throws Exception {
            Method m=allocation.getDeclaredMethod("vertexFirst",int.class); m.setAccessible(true); return (Integer)m.invoke(mesh,part);
        }
        void free(Object mesh) throws Exception { invoke("free",new Class<?>[]{allocation},mesh); }
        int pages() throws Exception { return (Integer)invoke("pageCount",new Class<?>[0]); }
        int buffer(int address) throws Exception { return buffer(address,"vertices"); }
        int indexBuffer(int address) throws Exception { return buffer(address,"indices"); }
        int buffer(int address,String field) throws Exception {
            Object page=invoke("page",new Class<?>[]{int.class},address);
            Field f=page.getClass().getDeclaredField(field); f.setAccessible(true); return f.getInt(page);
        }
        Object invoke(String name,Class<?>[] signature,Object... args) throws Exception {
            Method m=type.getDeclaredMethod(name,signature); m.setAccessible(true);
            try { return m.invoke(storage,args); }
            catch(InvocationTargetException e) {
                if(e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                if(e.getCause() instanceof Error) throw (Error)e.getCause(); throw e;
            }
        }
    }
    public static final class Driver {
        static final Map<Integer,byte[]> data=new HashMap<>();
        static int binding,next,allocations,uploads,failAllocation,failUpload;
        static long uploaded,peakBytes;
        static void reset() { data.clear();binding=101;next=allocations=uploads=failAllocation=failUpload=0;uploaded=peakBytes=0; }
        static long liveBytes() { long total=0; for(byte[] b:data.values())total+=b.length;return total; }
        public static int glGetInteger(int p) { assertEquals(0x8894,p);return binding; }
        public static int glGenBuffers() { data.put(++next,new byte[0]);return next; }
        public static void glBindBuffer(int target,int buffer) { assertEquals(0x8892,target);binding=buffer; }
        public static void glBufferData(int target,long bytes,int usage) {
            if(++allocations!=failAllocation)data.put(binding,new byte[(int)bytes]);
            peakBytes=Math.max(peakBytes,liveBytes());
        }
        public static int glGetBufferParameteri(int target,int param) { return data.get(binding).length; }
        public static void glBufferSubData(int target,long offset,ByteBuffer source) {
            if(++uploads==failUpload)throw new IllegalStateException("injected upload failure");
            uploaded+=source.remaining();source.duplicate().get(data.get(binding),(int)offset,source.remaining());
        }
        public static void glDeleteBuffers(int name) { data.remove(name);if(binding==name)binding=0; }
    }
}
