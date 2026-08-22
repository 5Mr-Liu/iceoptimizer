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

public final class RenderPassLifecycleAdapterTest {
    @After public void reset() {
        RenderPassBootstrap.resetForTest();
        Bridge.reset();
    }

    @Test
    public void wrapsAllThreeExactVanillaCallsWithCatchAllCleanup() throws Exception {
        byte[] transformed = transform(synthetic(true));
        assertEquals(1, calls(transformed, RenderPassLifecycleAdapter.BOOTSTRAP,
            "beginSky"));
        assertEquals(1, calls(transformed, RenderPassLifecycleAdapter.BOOTSTRAP,
            "beginWeather"));
        assertEquals(1, calls(transformed, RenderPassLifecycleAdapter.BOOTSTRAP,
            "beginHand"));
        assertEquals(3, calls(transformed, RenderPassLifecycleAdapter.BOOTSTRAP,
            "end"));
        assertEquals(3, calls(transformed, RenderPassLifecycleAdapter.BOOTSTRAP,
            "abort"));
        assertEquals(1, calls(transformed, RenderPassLifecycleAdapter.RENDER_GLOBAL,
            "func_174976_a"));
        verify(transformed);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedVanillaCallGraph() {
        transform(synthetic(false));
    }

    @Test
    public void bootstrapFailsOpenAndPreservesOriginalThrowableIdentity() {
        assertTrue(RenderPassBootstrap.install(Bridge.class));
        assertEquals(11L, RenderPassBootstrap.beginSky());
        assertEquals(12L, RenderPassBootstrap.beginWeather());
        assertEquals(13L, RenderPassBootstrap.beginHand());
        RenderPassBootstrap.end(13L);
        RuntimeException original = new RuntimeException("original");
        RenderPassBootstrap.abort(12L, original);
        assertEquals(1, Bridge.ends);
        assertEquals(1, Bridge.aborts);
        assertSame(original, Bridge.error);
    }

    @Test
    public void transformsProductionSrgEntityRendererWhenFixtureIsProvided()
        throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge SRG jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] original = read(jar, RenderPassLifecycleAdapter.ENTITY_RENDERER);
            byte[] transformed = transform(original);
            assertFalse(Arrays.equals(original, transformed));
            verify(transformed);
        } finally {
            jar.close();
        }
    }

    private static byte[] transform(byte[] original) {
        byte[] transformed = new RenderPassLifecycleAdapter().transform(
            "net.minecraft.client.renderer.EntityRenderer", original,
            new TargetSpec("net.minecraft.client.renderer.EntityRenderer",
                "modern-frame-coordinator", "test",
                Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] synthetic(boolean includeHand) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            RenderPassLifecycleAdapter.ENTITY_RENDERER, null,
            "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE,
            RenderPassLifecycleAdapter.WORLD_PASS,
            RenderPassLifecycleAdapter.WORLD_PASS_DESC, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.FLOAD, 2);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            RenderPassLifecycleAdapter.RENDER_GLOBAL, "func_174976_a",
            "(FI)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.FLOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            RenderPassLifecycleAdapter.ENTITY_RENDERER, "func_78474_d",
            "(F)V", false);
        if (includeHand) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.FLOAD, 2);
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                RenderPassLifecycleAdapter.ENTITY_RENDERER, "func_78476_b",
                "(FI)V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        empty(writer, Opcodes.ACC_PROTECTED, "func_78474_d", "(F)V");
        empty(writer, Opcodes.ACC_PRIVATE, "func_78476_b", "(FI)V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void empty(ClassWriter writer, int access, String name,
                              String descriptor) {
        MethodVisitor method = writer.visitMethod(access, name, descriptor,
            null, null);
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
        private static Throwable error;
        public static long beginSky() { return 11L; }
        public static long beginWeather() { return 12L; }
        public static long beginHand() { return 13L; }
        public static void end(long token) { ends++; }
        public static void abort(long token, Throwable failure) {
            aborts++;
            error = failure;
        }
        private static void reset() {
            ends = 0;
            aborts = 0;
            error = null;
        }
    }
}
