package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces only Block's static shape-to-list helper, after dynamic shape evaluation. */
final class CollisionBoxAllocationAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/block/Block";
    static final String DESC = "(Lnet/minecraft/util/math/BlockPos;"
        + "Lnet/minecraft/util/math/AxisAlignedBB;Ljava/util/List;"
        + "Lnet/minecraft/util/math/AxisAlignedBB;)V";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/entity/CollisionBoxBridge";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int found = 0;
        for (MethodNode method : node.methods) {
            if (!DESC.equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
            found++;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            for (int i = 0; i < 4; i++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, i));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "append", DESC, false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.maxStack = 4;
            method.maxLocals = 4;
        }
        if (!TARGET.equals(node.name) || found != 1) {
            throw new IllegalStateException("Block collision helper changed: " + node.name + '/' + found);
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
