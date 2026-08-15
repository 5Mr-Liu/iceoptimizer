package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Caches OTG's tiny, repeatedly lowercased configuration-function name set. */
final class OtgResourcesAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/optimizer/compat/otg/OtgParsingBridge";
    static final String DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG CustomObjectResourcesManager 类名变化：" + node.name);
        }

        int replacements = 0;
        for (MethodNode method : node.methods) {
            if (!"registerConfigFunction".equals(method.name) && !"getConfigFunction".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && "java/lang/String".equals(call.owner)
                    && "toLowerCase".equals(call.name) && "()Ljava/lang/String;".equals(call.desc)) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = BRIDGE_OWNER;
                    call.name = "lowercaseFunctionName";
                    call.desc = DESCRIPTOR;
                    call.itf = false;
                    replacements++;
                }
            }
        }
        if (replacements != 4) {
            throw new IllegalStateException("OTG 函数名 lowercase 调用数量应为 4，实际 " + replacements);
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
