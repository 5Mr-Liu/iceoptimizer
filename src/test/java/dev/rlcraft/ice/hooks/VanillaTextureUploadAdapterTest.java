package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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

public final class VanillaTextureUploadAdapterTest {
    @Test
    public void transformsReviewedForgeSrgTextureUtilWhenProvided() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            byte[] original = read(jar, VanillaTextureUploadAdapter.TARGET.replace('/', '.'));
            TargetSpec target = null;
            for (TargetSpec candidate : OptimizerTargetCatalog.findAll(
                VanillaTextureUploadAdapter.TARGET.replace('/', '.'))) {
                if ("vanilla-texture-pbo-upload".equals(candidate.adapterId)) target = candidate;
            }
            assertNotNull(target);
            byte[] transformed = new IceOptimizerTransformer().transform(
                target.className, target.className, original);
            assertFalse(Arrays.equals(original, transformed));
            assertEquals(1, countCalls(transformed, VanillaTextureUploadAdapter.BRIDGE,
                "tryUploadLevel", VanillaTextureUploadAdapter.BRIDGE_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, VanillaTextureUploadAdapter.TARGET,
                VanillaTextureUploadAdapter.ORIGINAL_UPLOAD,
                VanillaTextureUploadAdapter.TARGET_DESCRIPTOR));
            new ClassReader(transformed);
        } finally {
            jar.close();
        }
    }

    @Test
    public void wrapsTheSingleLevelUploadAndKeepsFallback() {
        byte[] original = syntheticTextureUtil();
        TargetSpec target = new TargetSpec(VanillaTextureUploadAdapter.TARGET.replace('/', '.'),
            "foamfix-texture-upload", "vanilla-texture-pbo-upload",
            Collections.<String>emptySet());
        byte[] transformed = new VanillaTextureUploadAdapter().transform(
            target.className, original, target);
        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, VanillaTextureUploadAdapter.BRIDGE,
            "tryUploadLevel", VanillaTextureUploadAdapter.BRIDGE_DESCRIPTOR));
        assertEquals(1, countCalls(transformed, VanillaTextureUploadAdapter.TARGET,
            VanillaTextureUploadAdapter.ORIGINAL_UPLOAD,
            VanillaTextureUploadAdapter.TARGET_DESCRIPTOR));
        new ClassReader(transformed);
    }

    private static byte[] syntheticTextureUtil() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, VanillaTextureUploadAdapter.TARGET,
            null, "java/lang/Object", null);
        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "func_147947_a", VanillaTextureUploadAdapter.TARGET_DESCRIPTOR, null, null);
        upload.visitCode();
        upload.visitIntInsn(Opcodes.SIPUSH, 3553);
        upload.visitVarInsn(Opcodes.ILOAD, 0);
        upload.visitVarInsn(Opcodes.ILOAD, 4);
        upload.visitVarInsn(Opcodes.ILOAD, 5);
        upload.visitVarInsn(Opcodes.ILOAD, 2);
        upload.visitVarInsn(Opcodes.ILOAD, 3);
        upload.visitLdcInsn(Integer.valueOf(32993));
        upload.visitLdcInsn(Integer.valueOf(33639));
        upload.visitInsn(Opcodes.ACONST_NULL);
        upload.visitMethodInsn(Opcodes.INVOKESTATIC,
            "net/minecraft/client/renderer/GlStateManager", "func_187414_b",
            "(IIIIIIIILjava/nio/IntBuffer;)V", false);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(0, 0);
        upload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static int countCalls(byte[] bytes, final String owner, final String name,
                                  final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String methodDescriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
