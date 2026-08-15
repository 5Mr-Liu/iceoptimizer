package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class IceClientOptimizerTransformerTest {
    @Test
    public void unknownTargetFingerprintIsNeverModified() {
        String name = "com.dhanantry.scapeandrunparasites.client.renderer.entity.misc.RenderOrbBoom";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        assertArrayEquals(original, new IceClientOptimizerTransformer().transform(name, name, original));
    }
}
