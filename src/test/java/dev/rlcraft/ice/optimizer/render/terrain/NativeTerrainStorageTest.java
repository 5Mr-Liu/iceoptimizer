package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

/** Fault injection stays in tests; production calls LWJGL directly. */
public class NativeTerrainStorageTest {
    @Before public void reset() {
        Driver.sizes.clear(); Driver.next = 0; Driver.read = 101; Driver.write = 102;
        Driver.failAllocation = false; Driver.throwAllocation = false; Driver.zeroName = false;
        Driver.pendingError = 1282; Driver.queries = 0;
    }

    @Test public void unrelatedGlErrorDoesNotRejectSuccessfulStorage() throws Exception {
        Fixture fixture = new Fixture();
        fixture.ensure(180000);
        assertEquals(5505024L, (long) Driver.sizes.get(fixture.value("buffer")));
        assertEquals(1282, Driver.pendingError);
        assertEquals(101, Driver.read); assertEquals(102, Driver.write);
        int queries = Driver.queries;
        fixture.ensure(180000);
        assertEquals("no per-upload size query", queries, Driver.queries);
    }

    @Test public void failedFirstAllocationIsNotAcceptedOrLeaked() throws Exception {
        Fixture fixture = new Fixture(); Driver.failAllocation = true;
        try { fixture.ensure(180000); fail(); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("requested=5505024, allocated=0"));
        }
        assertEquals(0, fixture.value("buffer")); assertEquals(0, fixture.value("capacity"));
        assertTrue(Driver.sizes.isEmpty());
        assertEquals(101, Driver.read); assertEquals(102, Driver.write);
    }

    @Test public void failedGrowthRetainsExistingStorageAndBindings() throws Exception {
        Fixture fixture = new Fixture(); fixture.ensure(1);
        int old = fixture.value("buffer"), capacity = fixture.value("capacity");
        Driver.read = old; Driver.write = old; Driver.failAllocation = true;
        try { fixture.ensure(capacity + 1); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(old, fixture.value("buffer")); assertEquals(capacity, fixture.value("capacity"));
        assertEquals(1, Driver.sizes.size()); assertTrue(Driver.sizes.containsKey(old));
        assertEquals(old, Driver.read); assertEquals(old, Driver.write);
        Driver.failAllocation = false;
        fixture.ensure(capacity + 1);
        assertNotEquals(old, fixture.value("buffer")); assertFalse(Driver.sizes.containsKey(old));
        assertEquals(fixture.value("buffer"), Driver.read); assertEquals(Driver.read, Driver.write);
    }

    @Test public void JavaFailureAlsoReleasesReplacementWithoutLosingOldStorage() throws Exception {
        Fixture fixture = new Fixture(); fixture.ensure(1);
        int old = fixture.value("buffer"), capacity = fixture.value("capacity");
        Driver.throwAllocation = true;
        try { fixture.ensure(capacity + 1); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(old, fixture.value("buffer")); assertEquals(capacity, fixture.value("capacity"));
        assertEquals(1, Driver.sizes.size()); assertTrue(Driver.sizes.containsKey(old));
        assertEquals(101, Driver.read); assertEquals(102, Driver.write);
    }

    @Test public void zeroBufferNameFailsBeforeTouchingBindings() throws Exception {
        Fixture fixture = new Fixture(); Driver.zeroName = true;
        try { fixture.ensure(1); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("creation failed")); }
        assertEquals(101, Driver.read); assertEquals(102, Driver.write);
        assertEquals(0, fixture.value("capacity")); assertTrue(Driver.sizes.isEmpty());
    }

    private static final class Fixture extends ClassLoader {
        final Object storage;
        final Class<?> type;
        Fixture() throws Exception {
            super(NativeTerrainStorageTest.class.getClassLoader());
            final String name = "dev.rlcraft.ice.optimizer.render.terrain.NativeTerrainStorage";
            ClassReader reader = new ClassReader(name);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, method, desc, signature, exceptions)) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String descriptor, boolean itf) {
                            if (owner.startsWith("org/lwjgl/opengl/")) owner = Type.getInternalName(Driver.class);
                            super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                        }
                    };
                }
            }, 0);
            byte[] bytes = writer.toByteArray();
            type = defineClass(name, bytes, 0, bytes.length);
            Constructor<?> constructor = type.getDeclaredConstructor(int.class);
            constructor.setAccessible(true); storage = constructor.newInstance(28);
        }
        void ensure(int count) throws Exception {
            Method method = type.getDeclaredMethod("ensure", int.class); method.setAccessible(true);
            try { method.invoke(storage, count); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException) throw (RuntimeException) failure.getCause();
                if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
                throw failure;
            }
        }
        int value(String name) throws Exception {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field.getInt(storage);
        }
    }

    public static final class Driver {
        static final Map<Integer, Long> sizes = new HashMap<>();
        static int next, read, write, pendingError, queries;
        static boolean failAllocation, throwAllocation, zeroName;
        public static int glGetInteger(int parameter) { return parameter == 0x8F36 ? read : write; }
        public static int glGenBuffers() {
            if (zeroName) return 0;
            sizes.put(++next, 0L); return next;
        }
        public static void glBindBuffer(int target, int buffer) {
            if (target == 0x8F36) read = buffer; else write = buffer;
        }
        public static void glBufferData(int target, long size, int usage) {
            if (throwAllocation) throw new IllegalArgumentException("injected driver wrapper failure");
            if (!failAllocation) sizes.put(write, size);
        }
        public static long glGetBufferParameteri64(int target, int parameter) { queries++; return sizes.get(write); }
        public static void glDeleteBuffers(int buffer) {
            sizes.remove(buffer); if (read == buffer) read = 0; if (write == buffer) write = 0;
        }
        public static int glGetError() { int error = pendingError; pendingError = 0; return error; }
        public static void glCopyBufferSubData(int source, int destination, long from, long to, long size) { }
    }
}
