package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Reuses only the two reviewed immutable particle argument arrays. */
final class IceAndFireSeaSerpentAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/github/alexthe666/iceandfire/entity/EntitySeaSerpent";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/iceandfire/IceAndFireOptimizationBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Ice and Fire SeaSerpent 类名变化：" + node.name);
        int aroundEmpty = 0;
        int slamEmpty = 0;
        int zero = 0;
        for (MethodNode method : node.methods) {
            if ("spawnParticlesAroundEntity".equals(method.name)) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof IntInsnNode && instruction.getOpcode() == Opcodes.NEWARRAY
                        && ((IntInsnNode) instruction).operand == Opcodes.T_INT) {
                        AbstractInsnNode length = previousOpcode(instruction);
                        if (length != null && length.getOpcode() == Opcodes.ICONST_0) {
                            method.instructions.remove(length);
                            method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                BRIDGE, "emptyParticleArgs", "()[I", false));
                            aroundEmpty++;
                        }
                    }
                }
            } else if ("spawnSlamParticles".equals(method.name)) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof IntInsnNode) || instruction.getOpcode() != Opcodes.NEWARRAY
                        || ((IntInsnNode) instruction).operand != Opcodes.T_INT) continue;
                    AbstractInsnNode length = previousOpcode(instruction);
                    AbstractInsnNode duplicate = nextOpcode(instruction);
                    AbstractInsnNode index = nextOpcode(duplicate);
                    AbstractInsnNode value = nextOpcode(index);
                    AbstractInsnNode store = nextOpcode(value);
                    if (length != null && length.getOpcode() == Opcodes.ICONST_0) {
                        method.instructions.remove(length);
                        method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            BRIDGE, "emptyParticleArgs", "()[I", false));
                        slamEmpty++;
                    } else if (length != null && length.getOpcode() == Opcodes.ICONST_1
                        && duplicate != null && duplicate.getOpcode() == Opcodes.DUP
                        && index != null && index.getOpcode() == Opcodes.ICONST_0
                        && value != null && value.getOpcode() == Opcodes.ICONST_0
                        && store != null && store.getOpcode() == Opcodes.IASTORE) {
                        method.instructions.remove(length);
                        method.instructions.remove(duplicate);
                        method.instructions.remove(index);
                        method.instructions.remove(value);
                        method.instructions.remove(store);
                        method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            BRIDGE, "zeroParticleArgs", "()[I", false));
                        zero++;
                    }
                }
            }
        }
        if (aroundEmpty != 1 || slamEmpty + zero != 1) {
            throw new IllegalStateException("Ice and Fire SeaSerpent 粒子调用图变化：aroundEmpty="
                + aroundEmpty + ", slamEmpty=" + slamEmpty + ", zero=" + zero);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
