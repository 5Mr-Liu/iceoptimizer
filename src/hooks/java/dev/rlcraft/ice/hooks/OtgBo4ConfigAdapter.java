package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Replaces BO4Config's per-block 16x16 prefix scan with an identity-scoped prefix table. */
final class OtgBo4ConfigAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET_METHOD = "loadBlockArrays";
    static final String INDEX_METHOD = "getColumnBlockIndex";
    static final String INDEX_DESCRIPTOR = "([[SII)I";
    static final String BRIDGE_OWNER =
        "dev/rlcraft/ice/optimizer/compat/otg/OtgBo4OptimizationBridge";
    static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/Object;[[SII)I";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG BO4Config 类名变化：" + node.name);
        }

        MethodInsnNode indexCall = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (!TARGET_METHOD.equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && node.name.equals(call.owner)
                    && INDEX_METHOD.equals(call.name) && INDEX_DESCRIPTOR.equals(call.desc)) {
                    indexCall = call;
                    matches++;
                }
            }
        }
        if (matches != 1 || indexCall == null) {
            throw new IllegalStateException("OTG BO4Config 列索引调用数量应为 1，实际 " + matches);
        }
        indexCall.setOpcode(Opcodes.INVOKESTATIC);
        indexCall.owner = BRIDGE_OWNER;
        indexCall.name = "columnBlockIndex";
        indexCall.desc = BRIDGE_DESCRIPTOR;
        indexCall.itf = false;

        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
