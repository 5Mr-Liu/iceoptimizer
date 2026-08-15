package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class OreLibOpenGlStateAdapterTest {
    private static final String SYNTHETIC_INTERNAL = "dev/rlcraft/ice/hooks/SyntheticOreLibOpenGlState";
    private static final String REAL_CLASS = "org.orecruncher.lib.gfx.OpenGlState";
    private static final String REAL_ENTRY = "org/orecruncher/lib/gfx/OpenGlState.class";
    private static final String REAL_CLASS_SHA256 =
        "94f22feded017b278ff6bfd72fa51cc40fe4674a3b886cea2b1ec8ef3bbd5047";
    private static final String REAL_JAR_SHA256 =
        "4483d8cc2b0334b82b7d0f5bb6354a2020a0e00ce63a48379e8ba5eb7d79eef9";

    @Test
    public void replacesOnlyTheTwoReviewedPrivateQueryCalls() {
        byte[] original = syntheticClass(false);
        TargetSpec target = targetFor(SYNTHETIC_INTERNAL.replace('/', '.'));
        byte[] transformed = new OreLibOpenGlStateAdapter().transform(target.className, original, target);

        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            OreLibOpenGlStateAdapter.BRIDGE_OWNER, "getInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            OreLibOpenGlStateAdapter.BRIDGE_OWNER, "getFloat", OreLibOpenGlStateAdapter.FLOAT_DESCRIPTOR));
        assertEquals("the unrelated GL query must remain untouched", 1,
            countCalls(transformed, Opcodes.INVOKESTATIC, OreLibOpenGlStateAdapter.GL11_OWNER,
                "glGetInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR));
        assertEquals(0, countCalls(transformed, Opcodes.INVOKESTATIC,
            OreLibOpenGlStateAdapter.GL11_OWNER, "glGetFloat", OreLibOpenGlStateAdapter.FLOAT_DESCRIPTOR));
        new ClassReader(transformed);

        ByteLoader loader = new ByteLoader(getClass().getClassLoader());
        assertEquals(SYNTHETIC_INTERNAL.replace('/', '.'),
            loader.define(SYNTHETIC_INTERNAL.replace('/', '.'), transformed).getName());
    }

    @Test
    public void rejectsAChangedPrivateQueryCallGraph() {
        TargetSpec target = targetFor(SYNTHETIC_INTERNAL.replace('/', '.'));
        try {
            new OreLibOpenGlStateAdapter().transform(target.className, syntheticClass(true), target);
            fail("adapter must reject a changed OreLib query helper");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("调用图变化"));
            assertTrue(expected.getMessage().contains("glGetInteger=2"));
        }
    }

    @Test
    public void transformsAndDefinesTheConfiguredRealOreLibClassWhenAvailable() throws Exception {
        String configured = System.getProperty("ice.orelib.jar", "").trim();
        Assume.assumeTrue("run with -PoreLibJar=<jar> for the real-JAR integration test", !configured.isEmpty());
        File jarFile = new File(configured);
        Assume.assumeTrue("configured OreLib JAR must exist", jarFile.isFile());
        assertEquals("the configured JAR must be the reviewed OreLib 3.6.0.1 binary",
            REAL_JAR_SHA256, sha256(jarFile));

        byte[] original;
        JarFile jar = new JarFile(jarFile);
        try {
            JarEntry entry = jar.getJarEntry(REAL_ENTRY);
            assertTrue("reviewed OreLib OpenGlState class must exist", entry != null);
            original = readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }

        assertEquals(REAL_CLASS_SHA256, CoreClassFingerprint.sha256(original));
        byte[] transformed = new IceClientOptimizerTransformer().transform(REAL_CLASS, REAL_CLASS, original);
        assertFalse("the exact reviewed class hash must install the adapter", Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            OreLibOpenGlStateAdapter.BRIDGE_OWNER, "getInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            OreLibOpenGlStateAdapter.BRIDGE_OWNER, "getFloat", OreLibOpenGlStateAdapter.FLOAT_DESCRIPTOR));
        new ClassReader(transformed);

        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            ByteLoader loader = new ByteLoader(dependencies);
            assertEquals(REAL_CLASS, loader.define(REAL_CLASS, transformed).getName());
        } finally {
            dependencies.close();
        }
    }

    private static TargetSpec targetFor(String className) {
        return new TargetSpec(className, "orelib-gl-state", "orelib-gl-state-cache",
            Collections.<String>emptySet());
    }

    private static byte[] syntheticClass(boolean duplicateIntegerQuery) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTHETIC_INTERNAL, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor integer = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "getInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR, null, null);
        integer.visitCode();
        integer.visitVarInsn(Opcodes.ILOAD, 0);
        integer.visitMethodInsn(Opcodes.INVOKESTATIC, OreLibOpenGlStateAdapter.GL11_OWNER,
            "glGetInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR, false);
        if (duplicateIntegerQuery) {
            integer.visitVarInsn(Opcodes.ILOAD, 0);
            integer.visitMethodInsn(Opcodes.INVOKESTATIC, OreLibOpenGlStateAdapter.GL11_OWNER,
                "glGetInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR, false);
            integer.visitInsn(Opcodes.IADD);
        }
        integer.visitInsn(Opcodes.IRETURN);
        integer.visitMaxs(2, 1);
        integer.visitEnd();

        MethodVisitor floating = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "getFloat", OreLibOpenGlStateAdapter.FLOAT_DESCRIPTOR, null, null);
        floating.visitCode();
        floating.visitVarInsn(Opcodes.ILOAD, 0);
        floating.visitMethodInsn(Opcodes.INVOKESTATIC, OreLibOpenGlStateAdapter.GL11_OWNER,
            "glGetFloat", OreLibOpenGlStateAdapter.FLOAT_DESCRIPTOR, false);
        floating.visitInsn(Opcodes.FRETURN);
        floating.visitMaxs(1, 1);
        floating.visitEnd();

        MethodVisitor unrelated = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "unrelatedInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR, null, null);
        unrelated.visitCode();
        unrelated.visitVarInsn(Opcodes.ILOAD, 0);
        unrelated.visitMethodInsn(Opcodes.INVOKESTATIC, OreLibOpenGlStateAdapter.GL11_OWNER,
            "glGetInteger", OreLibOpenGlStateAdapter.INTEGER_DESCRIPTOR, false);
        unrelated.visitInsn(Opcodes.IRETURN);
        unrelated.visitMaxs(1, 1);
        unrelated.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countCalls(byte[] bytes, final int expectedOpcode, final String expectedOwner,
                                  final String expectedName, final String expectedDescriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean itf) {
                        if (opcode == expectedOpcode && expectedOwner.equals(owner)
                            && expectedName.equals(name) && expectedDescriptor.equals(descriptor)) {
                            count[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static byte[] readFully(InputStream input) throws Exception {
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

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
