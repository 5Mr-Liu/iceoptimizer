package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Keeps RLTweaker's complete-list APIs intact and specializes its nearest-only query. */
final class RltNearestEntityAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/charles445/rltweaker/util/WorldRadiusUtil";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/entity/NearestEntityQueryBridge";
    static final String DESC = "(Lnet/minecraft/world/World;Ljava/lang/Class;"
        + "Lnet/minecraft/util/math/AxisAlignedBB;Lnet/minecraft/entity/Entity;D)Lnet/minecraft/entity/Entity;";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        int found = 0;
        for (MethodNode method : node.methods) {
            if (!"findNearestEntityWithinAABB".equals(method.name) || !DESC.equals(method.desc)) continue;
            found++;
            InsnList code = new InsnList();
            LabelNode original = new LabelNode();
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, original));
            for (int i = 0; i < 4; i++) code.add(new VarInsnNode(Opcodes.ALOAD, i));
            code.add(new VarInsnNode(Opcodes.DLOAD, 4));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "nearest", DESC, false));
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(original);
            method.instructions.insert(code);
        }
        if (!TARGET.equals(node.name) || found != 1) {
            throw new IllegalStateException("RLT nearest query changed: " + node.name + '/' + found);
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
}
