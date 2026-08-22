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

public final class OptifineShaderLifecycleAdapterTest {
    @After public void reset() {
        OptifineShaderBootstrap.resetForTest();
        Bridge.failure = null;
        Bridge.ends = 0;
        Bridge.aborts = 0;
    }

    @Test
    public void wrapsTheAuthoritativeProgramLifecycleWithFinallySemantics() {
        byte[] transformed = transform(synthetic(true));
        assertTrue(hasMethod(transformed, OptifineShaderLifecycleAdapter.ORIGINAL,
            OptifineShaderLifecycleAdapter.USE_DESC));
        assertEquals(1, calls(transformed, OptifineShaderLifecycleAdapter.BOOTSTRAP,
            "begin"));
        assertEquals(1, calls(transformed, OptifineShaderLifecycleAdapter.BOOTSTRAP,
            "end"));
        assertEquals(1, calls(transformed, OptifineShaderLifecycleAdapter.BOOTSTRAP,
            "abort"));
    }

    @Test
    public void sameLogicalProgramFastReturnStillCrossesBothBridgeBoundaries() {
        byte[] transformed = transform(synthetic(true, true));
        assertEquals(1, calls(transformed,
            OptifineShaderLifecycleAdapter.BOOTSTRAP, "begin"));
        assertEquals(1, calls(transformed,
            OptifineShaderLifecycleAdapter.BOOTSTRAP, "end"));
        assertEquals(1, calls(transformed,
            OptifineShaderLifecycleAdapter.BOOTSTRAP, "abort"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedOptifineProgramGraph() {
        transform(synthetic(false));
    }

    @Test
    public void bootstrapDelegatesWithoutChangingOriginalExceptions() {
        assertTrue(OptifineShaderBootstrap.install(Bridge.class));
        Object program = new Object();
        assertEquals(7L, OptifineShaderBootstrap.begin(program));
        OptifineShaderBootstrap.end(7L, program);
        assertEquals(1, Bridge.ends);
        RuntimeException expected = new RuntimeException("original");
        OptifineShaderBootstrap.abort(7L, program, expected);
        assertSame(expected, Bridge.failure);
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
            byte[] original = read(jar, OptifineShaderLifecycleAdapter.SHADERS);
            assertTrue(hasStaticField(original, "renderWidth", "I"));
            assertTrue(hasStaticField(original, "renderHeight", "I"));
            assertTrue(hasStaticField(original, "shadowMapWidth", "I"));
            assertTrue(hasStaticField(original, "shadowMapHeight", "I"));
            assertTrue(hasStaticField(original, "usedColorBuffers", "I"));
            assertTrue(hasStaticField(original, "usedDepthBuffers", "I"));
            assertTrue(hasStaticField(original, "usedShadowColorBuffers", "I"));
            assertTrue(hasStaticField(original, "usedShadowDepthBuffers", "I"));
            assertTrue(hasStaticField(original, "gbuffersFormat", "[I"));
            byte[] transformed = transform(original);
            assertFalse(Arrays.equals(original, transformed));
            assertEquals(1, calls(transformed,
                OptifineShaderLifecycleAdapter.BOOTSTRAP, "begin"));
        } finally {
            jar.close();
        }
    }

    private static byte[] transform(byte[] original) {
        byte[] transformed = new OptifineShaderLifecycleAdapter().transform(
            "net.optifine.shaders.Shaders", original,
            new TargetSpec("net.optifine.shaders.Shaders",
                "optifine-shader-bridge", "test",
                Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] synthetic(boolean complete) {
        return synthetic(complete, false);
    }

    private static byte[] synthetic(boolean complete,
                                    boolean sameProgramFastReturn) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            OptifineShaderLifecycleAdapter.SHADERS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "activeProgram", "L" + OptifineShaderLifecycleAdapter.PROGRAM + ";",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "activeProgramID", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC
            | Opcodes.ACC_STATIC, OptifineShaderLifecycleAdapter.USE_PROGRAM,
            OptifineShaderLifecycleAdapter.USE_DESC, null, null);
        method.visitCode();
        if (sameProgramFastReturn) {
            Label changed = new Label();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETSTATIC,
                OptifineShaderLifecycleAdapter.SHADERS, "activeProgram",
                "L" + OptifineShaderLifecycleAdapter.PROGRAM + ";");
            method.visitJumpInsn(Opcodes.IF_ACMPNE, changed);
            method.visitInsn(Opcodes.RETURN);
            method.visitLabel(changed);
        }
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.PUTSTATIC,
            OptifineShaderLifecycleAdapter.SHADERS, "activeProgram",
            "L" + OptifineShaderLifecycleAdapter.PROGRAM + ";");
        int uses = complete ? 2 : 1;
        for (int index = 0; index < uses; index++) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                OptifineShaderLifecycleAdapter.PROGRAM, "getId", "()I", false);
            method.visitInsn(Opcodes.DUP);
            method.visitFieldInsn(Opcodes.PUTSTATIC,
                OptifineShaderLifecycleAdapter.SHADERS, "activeProgramID", "I");
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/ARBShaderObjects", "glUseProgramObjectARB",
                "(I)V", false);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            OptifineShaderLifecycleAdapter.SHADERS, "setDrawBuffers",
            "(Ljava/nio/IntBuffer;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean hasMethod(byte[] bytes, final String expected,
                                     final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (expected.equals(name) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasStaticField(byte[] bytes, final String expected,
                                          final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public org.objectweb.asm.FieldVisitor visitField(int access,
                    String name, String desc, String signature, Object value) {
                if (expected.equals(name) && descriptor.equals(desc)
                    && (access & Opcodes.ACC_STATIC) != 0) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int calls(byte[] bytes, final String owner,
                             final String expected) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode,
                                                          String actualOwner,
                                                          String actualName,
                                                          String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && expected.equals(actualName)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] read(JarFile jar, String owner) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        if (entry == null) throw new IllegalStateException("missing " + owner);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    public static final class Bridge {
        static Throwable failure;
        static int ends;
        static int aborts;
        public static long begin(Object program) { return 7L; }
        public static void end(long token, Object program) { ends++; }
        public static void abort(long token, Object program, Throwable error) {
            failure = error;
            aborts++;
        }
    }
}
