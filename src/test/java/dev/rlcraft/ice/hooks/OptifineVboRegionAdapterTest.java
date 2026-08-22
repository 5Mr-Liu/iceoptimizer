package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class OptifineVboRegionAdapterTest {
    @After public void reset() {
        OptifineRegionBootstrap.resetForTest();
        Bridge.ends = 0;
        Bridge.aborts = 0;
        Bridge.failure = null;
    }

    @Test
    public void validatesAndWrapsExactOptifineRegionEmitter() {
        byte[] transformed = transform(synthetic(true));
        assertTrue(hasInterface(transformed, OptifineVboRegionAdapter.ACCESS));
        assertTrue(hasMethod(transformed, OptifineVboRegionAdapter.ORIGINAL,
            "(Ltest/VertexFormat;)V"));
        assertEquals(1, calls(transformed, OptifineVboRegionAdapter.BOOTSTRAP,
            "begin"));
        assertEquals(1, calls(transformed, OptifineVboRegionAdapter.BOOTSTRAP,
            "end"));
        assertEquals(1, calls(transformed, OptifineVboRegionAdapter.BOOTSTRAP,
            "abort"));
        assertTrue(hasMethod(transformed, "ice$commandCapacity", "()I"));
        assertTrue(hasMethod(transformed, "ice$layer", "()Ljava/lang/Object;"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsChangedMultiDrawGraph() {
        transform(synthetic(false));
    }

    @Test
    public void bootstrapNeverChangesOriginalThrowableIdentity() {
        assertTrue(OptifineRegionBootstrap.install(Bridge.class));
        Object region = new Object();
        assertEquals(11L, OptifineRegionBootstrap.begin(region));
        OptifineRegionBootstrap.end(11L, region);
        assertEquals(1, Bridge.ends);
        RuntimeException original = new RuntimeException("original");
        OptifineRegionBootstrap.abort(11L, region, original);
        assertSame(original, Bridge.failure);
        assertEquals(1, Bridge.aborts);
    }

    @Test
    public void transformsReviewedOptifineG5WhenFixtureIsProvided() throws Exception {
        String configured = System.getProperty("ice.optifine.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar=<OptiFine G5 jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] original = read(jar, OptifineVboRegionAdapter.REGION);
            byte[] transformed = transform(original);
            assertFalse(Arrays.equals(original, transformed));
            assertTrue(hasInterface(transformed, OptifineVboRegionAdapter.ACCESS));
            assertEquals(1, calls(transformed,
                OptifineVboRegionAdapter.BOOTSTRAP, "begin"));
        } finally {
            jar.close();
        }
    }

    private static byte[] transform(byte[] original) {
        byte[] transformed = new OptifineVboRegionAdapter().transform(
            "net.optifine.render.VboRegion", original,
            new TargetSpec("net.optifine.render.VboRegion",
                "optifine-region-backend", "test",
                Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] synthetic(boolean complete) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            OptifineVboRegionAdapter.REGION, null, "java/lang/Object", null);
        field(writer, "layer", "Ljava/lang/Object;");
        field(writer, "bufferIndexVertex", "Ljava/nio/IntBuffer;");
        field(writer, "bufferCountVertex", "Ljava/nio/IntBuffer;");
        field(writer, "drawMode", "I");
        field(writer, "glBufferId", "I");
        field(writer, "positionTop", "I");
        field(writer, "sizeUsed", "I");

        MethodVisitor bind = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "bindBuffer", "()V", null, null);
        bind.visitCode();
        bind.visitInsn(Opcodes.RETURN);
        bind.visitMaxs(0, 0);
        bind.visitEnd();
        MethodVisitor compact = writer.visitMethod(Opcodes.ACC_PRIVATE,
            "compactRanges", "(I)V", null, null);
        compact.visitCode();
        compact.visitInsn(Opcodes.RETURN);
        compact.visitMaxs(0, 0);
        compact.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            OptifineVboRegionAdapter.FINISH, "(Ltest/VertexFormat;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            OptifineVboRegionAdapter.REGION, "bindBuffer", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/VertexFormat",
            "setup", "()V", false);
        flip(method, "bufferIndexVertex");
        flip(method, "bufferCountVertex");
        if (complete) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
                "drawMode", "I");
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
                "bufferIndexVertex", "Ljava/nio/IntBuffer;");
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
                "bufferCountVertex", "Ljava/nio/IntBuffer;");
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Gl",
                "glMultiDrawArrays",
                "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V", false);
        }
        resetLimit(method, "bufferIndexVertex");
        resetLimit(method, "bufferCountVertex");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
            "positionTop", "I");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
            "sizeUsed", "I");
        method.visitIntInsn(Opcodes.BIPUSH, 11);
        method.visitInsn(Opcodes.IMUL);
        method.visitIntInsn(Opcodes.BIPUSH, 10);
        method.visitInsn(Opcodes.IDIV);
        Label done = new Label();
        method.visitJumpInsn(Opcodes.IF_ICMPLE, done);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
            OptifineVboRegionAdapter.REGION, "compactRanges", "(I)V", false);
        method.visitLabel(done);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void flip(MethodVisitor method, String field) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
            field, "Ljava/nio/IntBuffer;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer",
            "flip", "()Ljava/nio/Buffer;", false);
        method.visitInsn(Opcodes.POP);
    }

    private static void resetLimit(MethodVisitor method, String field) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
            field, "Ljava/nio/IntBuffer;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, OptifineVboRegionAdapter.REGION,
            field, "Ljava/nio/IntBuffer;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer",
            "capacity", "()I", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer",
            "limit", "(I)Ljava/nio/Buffer;", false);
        method.visitInsn(Opcodes.POP);
    }

    private static void field(ClassWriter writer, String name, String descriptor) {
        writer.visitField(Opcodes.ACC_PRIVATE, name, descriptor, null, null).visitEnd();
    }

    private static boolean hasInterface(byte[] bytes, String expected) {
        final boolean[] found = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name,
                                        String signature, String superName,
                                        String[] interfaces) {
                if (interfaces != null) for (String value : interfaces) {
                    if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE);
        return found[0];
    }

    private static boolean hasMethod(byte[] bytes, final String name,
                                     final String descriptor) {
        final boolean[] found = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (name.equals(method) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return found[0];
    }

    private static int calls(byte[] bytes, final String owner, final String name) {
        final int[] count = { 0 };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String callOwner,
                                                          String callName,
                                                          String callDesc,
                                                          boolean itf) {
                        if (owner.equals(callOwner) && name.equals(callName)) count[0]++;
                    }
                };
            }
        }, 0);
        return count[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        Assume.assumeNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    public static final class Bridge {
        private static int ends;
        private static int aborts;
        private static Throwable failure;
        public static long begin(Object region) { return 11L; }
        public static void end(long token, Object region) { ends++; }
        public static void abort(long token, Object region, Throwable error) {
            aborts++;
            failure = error;
        }
    }
}
