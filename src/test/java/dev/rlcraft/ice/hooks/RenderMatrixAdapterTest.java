package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class RenderMatrixAdapterTest {
    @Test
    public void publishesAlreadyCapturedMatricesExactlyOnce() {
        byte[] original = syntheticActiveRenderInfo();
        byte[] transformed = new RenderMatrixAdapter().transform(RenderMatrixAdapter.TARGET,
            original, new TargetSpec(RenderMatrixAdapter.TARGET,
                "modern-visibility-hzb", "test", Collections.<String>emptySet()));
        new ClassReader(transformed);
        assertEquals(1, countCalls(transformed, RenderMatrixAdapter.BRIDGE, "capture",
            "(Ljava/nio/FloatBuffer;Ljava/nio/FloatBuffer;Ljava/nio/IntBuffer;)V"));
    }

    private static byte[] syntheticActiveRenderInfo() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, RenderMatrixAdapter.TARGET,
            null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "field_178812_b", "Ljava/nio/FloatBuffer;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "field_178813_c", "Ljava/nio/FloatBuffer;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "field_178814_a", "Ljava/nio/IntBuffer;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "updateRenderInfo", "(Lnet/minecraft/entity/Entity;Z)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualDescriptor,
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
