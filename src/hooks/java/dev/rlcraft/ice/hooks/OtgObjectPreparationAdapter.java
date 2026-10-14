package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Runs once at each actual global/preset indexing operation, not on object lookup hits. */
final class OtgObjectPreparationAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/otg/OtgObjectPreparationBridge";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes); ClassNode node = new ClassNode(); reader.accept(node, 0);
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (!"indexGlobalObjectsFolder".equals(method.name) && !"indexWorldObjectsFolder".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!"indexAllCustomObjectFilesInDir".equals(call.name)) continue;
                int map = method.maxLocals++;
                InsnList capture = new InsnList(); capture.add(new InsnNode(Opcodes.DUP)); capture.add(new VarInsnNode(Opcodes.ASTORE, map));
                method.instructions.insertBefore(call, capture);
                InsnList prepare = new InsnList(); prepare.add(new VarInsnNode(Opcodes.ALOAD, map));
                prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "prepare", "(Ljava/util/Map;)V", false));
                method.instructions.insert(call, prepare); matches++;
            }
        }
        if (matches != 2) throw new IllegalStateException("OTG registration boundaries changed: " + matches);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
