package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Publishes explicit OTG reload/shutdown generations before cacheable reads. */
final class OtgConfigurationGenerationAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/pg85/otg/customobjects/CustomObjectManager";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/otg/OtgSynchronousIoBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG CustomObjectManager 类名变化：" + node.name);
        }

        install(node, "reloadCustomObjectFiles");
        install(node, "shutdown");

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void install(ClassNode node, String name) {
        MethodNode target = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && "()V".equals(method.desc)) {
                target = method;
                matches++;
            }
        }
        if (matches != 1 || target == null) {
            throw new IllegalStateException("OTG CustomObjectManager " + name
                + "()V 匹配应为 1，实际 " + matches);
        }
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : target.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC && BRIDGE.equals(call.owner)
                    && "advanceConfigurationGeneration".equals(call.name)) {
                    throw new IllegalStateException("OTG generation hook 重复安装：" + name);
                }
            }
        }
        InsnList prefix = new InsnList();
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "advanceConfigurationGeneration", "()V", false));
        target.instructions.insert(prefix);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
