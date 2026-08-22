package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.InputStream;
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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class GlStateTrackingAdapterTest {
    @Test
    public void syntheticWrappersPublishAfterEveryNormalReturn() {
        byte[] helper = transform(GlStateTrackingAdapter.Part.OPENGL_HELPER,
            GlStateTrackingAdapter.OPENGL_HELPER,
            syntheticHelper(GlStateTrackingAdapter.OPENGL_HELPER));
        assertEquals(2, countCalls(helper, "func_153161_d", "useProgram"));
        assertEquals(2, countCalls(helper, "func_153171_g", "bindFramebuffer"));
        assertEquals(2, countCalls(helper, "func_176072_g", "bindBuffer"));
        assertEquals(1, countCalls(helper, "func_77472_b", "clientActiveTexture"));
        assertEquals(1, countCalls(helper, "func_148821_a", "blendFunction"));

        byte[] manager = transform(GlStateTrackingAdapter.Part.GL_STATE_MANAGER,
            GlStateTrackingAdapter.GL_STATE_MANAGER,
            syntheticManager(GlStateTrackingAdapter.GL_STATE_MANAGER));
        assertEquals(2, countCalls(manager, "func_179143_c", "depthFunction"));
        assertEquals(1, countCalls(manager, "func_179138_g", "activeTexture"));
        assertEquals(1, countCalls(manager, "func_179144_i", "bindTexture"));
        assertEquals(1, countCalls(manager, "func_179147_l", "blendEnabled"));
        assertEquals(1, countCalls(manager, "func_179084_k", "blendEnabled"));
        assertEquals(1, countCalls(manager, "func_179098_w", "texture2dEnabled"));
        assertEquals(1, countCalls(manager, "func_179090_x", "texture2dEnabled"));
        assertEquals(1, countCalls(manager, "func_179112_b", "blendFunction"));
        assertEquals(1, countCalls(manager, "func_179120_a", "blendFunction"));
        assertEquals(1, countCalls(manager, "func_187398_d", "blendEquation"));
        assertEquals(1, countCalls(manager, "func_179126_j", "depthEnabled"));
        assertEquals(1, countCalls(manager, "func_179097_i", "depthEnabled"));
        assertEquals(1, countCalls(manager, "func_179132_a", "depthMask"));
        assertEquals(1, countCalls(manager, "func_179089_o", "cullEnabled"));
        assertEquals(1, countCalls(manager, "func_179129_p", "cullEnabled"));
        assertEquals(1, countCalls(manager, "func_179145_e", "lightingEnabled"));
        assertEquals(1, countCalls(manager, "func_179140_f", "lightingEnabled"));
        assertEquals(1, countCalls(manager, "func_179107_e", "cullFace"));
        assertEquals(1, countCalls(manager, "func_179135_a", "colorMask"));
        assertEquals(1, countCalls(manager, "func_179083_b", "viewport"));
        assertEquals(1, countCalls(manager, "func_179131_c", "color"));
        assertEquals(1, countCalls(manager, "func_179124_c", "color"));
        assertEquals(1, countCalls(manager, "func_179117_G", "resetColor"));
        assertEquals(1, countOwnerCalls(manager,
            GlStateTrackingAdapter.MATRIX_TRACKER, "rotate"));
        assertEquals(2, countOwnerCalls(manager,
            GlStateTrackingAdapter.MATRIX_TRACKER, "scale"));
        assertEquals(2, countOwnerCalls(manager,
            GlStateTrackingAdapter.MATRIX_TRACKER, "translate"));
        assertEquals(1, countOwnerCalls(manager,
            GlStateTrackingAdapter.MATRIX_TRACKER, "ortho"));
    }

    @Test
    public void realForgeSrgWrappersTransformAndVerify() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge SRG jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            verifyReal(jar, GlStateTrackingAdapter.Part.OPENGL_HELPER,
                GlStateTrackingAdapter.OPENGL_HELPER);
            verifyReal(jar, GlStateTrackingAdapter.Part.GL_STATE_MANAGER,
                GlStateTrackingAdapter.GL_STATE_MANAGER);
        } finally {
            jar.close();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsInstanceMethodLookalikes() {
        transform(GlStateTrackingAdapter.Part.GL_STATE_MANAGER,
            GlStateTrackingAdapter.GL_STATE_MANAGER,
            syntheticInstanceManager(GlStateTrackingAdapter.GL_STATE_MANAGER));
    }

    private static void verifyReal(JarFile jar, GlStateTrackingAdapter.Part part,
                                   String name) throws Exception {
        byte[] transformed = transform(part, name, read(jar, name));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static byte[] transform(GlStateTrackingAdapter.Part part, String name,
                                    byte[] original) {
        return new GlStateTrackingAdapter(part).transform(name, original,
            new TargetSpec(name, "modern-visibility-hzb", "test",
                Collections.<String>emptySet()));
    }

    private static byte[] syntheticHelper(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
            "java/lang/Object", null);
        addTwoReturnStatic(writer, "func_153161_d", "(I)V", 0);
        addTwoReturnStatic(writer, "func_153171_g", "(II)V", 0);
        addTwoReturnStatic(writer, "func_176072_g", "(II)V", 0);
        addReturnStatic(writer, "func_77472_b", "(I)V");
        addReturnStatic(writer, "func_148821_a", "(IIII)V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] syntheticManager(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
            "java/lang/Object", null);
        addTwoReturnStatic(writer, "func_179143_c", "(I)V", 0);
        addReturnStatic(writer, "func_179138_g", "(I)V");
        addReturnStatic(writer, "func_179144_i", "(I)V");
        addReturnStatic(writer, "func_179147_l", "()V");
        addReturnStatic(writer, "func_179084_k", "()V");
        addReturnStatic(writer, "func_179098_w", "()V");
        addReturnStatic(writer, "func_179090_x", "()V");
        addReturnStatic(writer, "func_179112_b", "(II)V");
        addReturnStatic(writer, "func_179120_a", "(IIII)V");
        addReturnStatic(writer, "func_187398_d", "(I)V");
        addReturnStatic(writer, "func_179126_j", "()V");
        addReturnStatic(writer, "func_179097_i", "()V");
        addReturnStatic(writer, "func_179132_a", "(Z)V");
        addReturnStatic(writer, "func_179089_o", "()V");
        addReturnStatic(writer, "func_179129_p", "()V");
        addReturnStatic(writer, "func_179145_e", "()V");
        addReturnStatic(writer, "func_179140_f", "()V");
        addReturnStatic(writer, "func_179107_e", "(I)V");
        addReturnStatic(writer, "func_179135_a", "(ZZZZ)V");
        addReturnStatic(writer, "func_179083_b", "(IIII)V");
        addReturnStatic(writer, "func_179131_c", "(FFFF)V");
        addReturnStatic(writer, "func_179124_c", "(FFF)V");
        addReturnStatic(writer, "func_179117_G", "()V");
        addReturnStatic(writer, "func_179128_n", "(I)V");
        addReturnStatic(writer, "func_179096_D", "()V");
        addReturnStatic(writer, "func_179094_E", "()V");
        addReturnStatic(writer, "func_179121_F", "()V");
        addReturnStatic(writer, "func_179114_b", "(FFFF)V");
        addReturnStatic(writer, "func_179152_a", "(FFF)V");
        addReturnStatic(writer, "func_179139_a", "(DDD)V");
        addReturnStatic(writer, "func_179109_b", "(FFF)V");
        addReturnStatic(writer, "func_179137_b", "(DDD)V");
        addReturnStatic(writer, "func_179110_a", "(Ljava/nio/FloatBuffer;)V");
        addReturnStatic(writer, "func_179130_a", "(DDDDDD)V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticInstanceManager(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
            "java/lang/Object", null);
        addTwoReturnStatic(writer, "func_179143_c", "(I)V", Opcodes.ACC_PUBLIC);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addTwoReturnStatic(ClassWriter writer, String name,
                                           String descriptor, int explicitAccess) {
        int access = explicitAccess == 0
            ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC : explicitAccess;
        MethodVisitor method = writer.visitMethod(access, name, descriptor,
            null, null);
        method.visitCode();
        org.objectweb.asm.Label second = new org.objectweb.asm.Label();
        int firstArgument = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        method.visitVarInsn(Opcodes.ILOAD, firstArgument);
        method.visitJumpInsn(Opcodes.IFEQ, second);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(second);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, firstArgument + 2);
        method.visitEnd();
    }

    private static void addReturnStatic(ClassWriter writer, String name,
                                        String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static int countCalls(byte[] bytes, final String methodName,
                                  final String trackerMethod) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                if (!methodName.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String name, String descriptor,
                                                          boolean itf) {
                        if (GlStateTrackingAdapter.TRACKER.equals(owner)
                            && trackerMethod.equals(name)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int countOwnerCalls(byte[] bytes, final String owner,
                                       final String methodName) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String descriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && methodName.equals(actualName)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        if (entry == null) throw new IllegalStateException("missing " + className);
        InputStream input = jar.getInputStream(entry);
        try {
            byte[] bytes = new byte[(int) entry.getSize()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IllegalStateException("truncated " + className);
                offset += read;
            }
            return bytes;
        } finally {
            input.close();
        }
    }
}
