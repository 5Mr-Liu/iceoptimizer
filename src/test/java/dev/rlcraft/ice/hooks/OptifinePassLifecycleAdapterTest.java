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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class OptifinePassLifecycleAdapterTest {
    @After public void reset() {
        OptifinePassBootstrap.resetForTest();
        Bridge.reset();
    }

    @Test
    public void wrapsPostStagesAndTransitionsAtTheExactFinalCall()
        throws Exception {
        byte[] transformed = transform(OptifinePassLifecycleAdapter.SHADERS,
            syntheticShaders(true));
        assertTrue(hasMethod(transformed,
            OptifinePassLifecycleAdapter.DEFERRED_ORIGINAL, "()V"));
        assertTrue(hasMethod(transformed,
            OptifinePassLifecycleAdapter.COMPOSITE_ORIGINAL, "()V"));
        assertEquals(1, calls(transformed, OptifinePassLifecycleAdapter.BOOTSTRAP,
            "beginDeferred"));
        assertEquals(1, calls(transformed, OptifinePassLifecycleAdapter.BOOTSTRAP,
            "beginComposite"));
        assertEquals(1, calls(transformed, OptifinePassLifecycleAdapter.BOOTSTRAP,
            "transitionFinal"));
        verify(transformed);
    }

    @Test
    public void wrapsTheReviewedShadowEntryWithoutWrappingItsInnerEmitters()
        throws Exception {
        byte[] transformed = transform(OptifinePassLifecycleAdapter.SHADERS_RENDER,
            syntheticShadersRender(true));
        assertTrue(hasMethod(transformed,
            OptifinePassLifecycleAdapter.SHADOW_ORIGINAL,
            "(Ljava/lang/Object;IFJ)V"));
        assertEquals(1, calls(transformed, OptifinePassLifecycleAdapter.BOOTSTRAP,
            "beginShadow"));
        assertEquals(1, calls(transformed, OptifinePassLifecycleAdapter.BOOTSTRAP,
            "endShadow"));
        assertEquals(1, calls(transformed, OptifinePassLifecycleAdapter.BOOTSTRAP,
            "abortShadow"));
        verify(transformed);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedCompositeGraph() {
        transform(OptifinePassLifecycleAdapter.SHADERS, syntheticShaders(false));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedShadowGraph() {
        transform(OptifinePassLifecycleAdapter.SHADERS_RENDER,
            syntheticShadersRender(false));
    }

    @Test
    public void bootstrapPreservesAbortThrowableAndAllStagePairings() {
        assertTrue(OptifinePassBootstrap.install(Bridge.class));
        assertEquals(21L, OptifinePassBootstrap.beginShadow());
        assertEquals(22L, OptifinePassBootstrap.beginDeferred());
        assertEquals(23L, OptifinePassBootstrap.beginComposite());
        OptifinePassBootstrap.endShadow(21L);
        OptifinePassBootstrap.endDeferred(22L);
        RuntimeException original = new RuntimeException("original");
        OptifinePassBootstrap.abortComposite(23L, original);
        OptifinePassBootstrap.transitionFinal();
        assertEquals(2, Bridge.ends);
        assertEquals(1, Bridge.aborts);
        assertEquals(1, Bridge.transitions);
        assertSame(original, Bridge.error);
    }

    @Test
    public void transformsReviewedOptifineG5WhenFixtureIsProvided()
        throws Exception {
        String configured = System.getProperty("ice.optifine.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar=<OptiFine G5 jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            assertReal(jar, OptifinePassLifecycleAdapter.SHADERS);
            assertReal(jar, OptifinePassLifecycleAdapter.SHADERS_RENDER);
        } finally {
            jar.close();
        }
    }

    private static void assertReal(JarFile jar, String owner) throws Exception {
        byte[] original = read(jar, owner);
        byte[] transformed = transform(owner, original);
        assertFalse(Arrays.equals(original, transformed));
        verify(transformed);
    }

    private static byte[] transform(String owner, byte[] original) {
        byte[] transformed = new OptifinePassLifecycleAdapter().transform(
            owner.replace('/', '.'), original,
            new TargetSpec(owner.replace('/', '.'), "optifine-shader-bridge",
                "test", Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] syntheticShaders(boolean includeFinal) {
        ClassWriter writer = writer(OptifinePassLifecycleAdapter.SHADERS);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "isShadowPass", "Z", null, null).visitEnd();
        emptyStatic(writer, "renderFinal", "()V");
        MethodVisitor composites = writer.visitMethod(Opcodes.ACC_PRIVATE
            | Opcodes.ACC_STATIC, "renderComposites",
            "([Lnet/optifine/shaders/Program;Z)V", null, null);
        composites.visitCode();
        if (includeFinal) composites.visitMethodInsn(Opcodes.INVOKESTATIC,
            OptifinePassLifecycleAdapter.SHADERS, "renderFinal", "()V", false);
        composites.visitInsn(Opcodes.RETURN);
        composites.visitMaxs(0, 0);
        composites.visitEnd();
        postEntry(writer, "renderDeferred", false);
        postEntry(writer, "renderCompositeFinal", true);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void postEntry(ClassWriter writer, String name,
                                  boolean finalStage) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC
            | Opcodes.ACC_STATIC, name, "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(finalStage ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            OptifinePassLifecycleAdapter.SHADERS, "renderComposites",
            "([Lnet/optifine/shaders/Program;Z)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] syntheticShadersRender(boolean complete) {
        ClassWriter writer = writer(OptifinePassLifecycleAdapter.SHADERS_RENDER);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC
            | Opcodes.ACC_STATIC, "renderShadowMap",
            "(Ljava/lang/Object;IFJ)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitFieldInsn(Opcodes.PUTSTATIC,
            OptifinePassLifecycleAdapter.SHADERS, "isShadowPass", "Z");
        if (complete) method.visitMethodInsn(Opcodes.INVOKESTATIC,
            OptifinePassLifecycleAdapter.SHADERS, "setCameraShadow", "()V", false);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitFieldInsn(Opcodes.PUTSTATIC,
            OptifinePassLifecycleAdapter.SHADERS, "isShadowPass", "Z");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter writer(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null,
            "java/lang/Object", null);
        return writer;
    }

    private static void emptyStatic(ClassWriter writer, String name,
                                    String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE
            | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static boolean hasMethod(byte[] bytes, final String expected,
                                     final String descriptor) {
        final boolean[] found = { false };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String desc,
                                                       String signature,
                                                       String[] exceptions) {
                if (expected.equals(name) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return found[0];
    }

    private static int calls(byte[] bytes, final String owner,
                             final String expected) {
        final int[] count = { 0 };
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
                        if (owner.equals(actualOwner)
                            && expected.equals(actualName)) count[0]++;
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
        private static int ends;
        private static int aborts;
        private static int transitions;
        private static Throwable error;
        public static long beginShadow() { return 21L; }
        public static void endShadow(long token) { ends++; }
        public static void abortShadow(long token, Throwable failure) {
            aborts++;
            error = failure;
        }
        public static long beginDeferred() { return 22L; }
        public static void endDeferred(long token) { ends++; }
        public static void abortDeferred(long token, Throwable failure) {
            aborts++;
            error = failure;
        }
        public static long beginComposite() { return 23L; }
        public static void endComposite(long token) { ends++; }
        public static void abortComposite(long token, Throwable failure) {
            aborts++;
            error = failure;
        }
        public static void transitionFinal() { transitions++; }
        private static void reset() {
            ends = 0;
            aborts = 0;
            transitions = 0;
            error = null;
        }
    }
}
