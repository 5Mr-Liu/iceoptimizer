package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Retains Forge's live serialization and replaces only the resulting NBT comparison. */
final class CapabilityNbtAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraftforge/common/capabilities/CapabilityDispatcher";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityNbtBridge";
    private static final String COMPOUND = "net/minecraft/nbt/NBTTagCompound";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Capability dispatcher target changed");
        MethodNode compatible = null;
        for (MethodNode method : node.methods) {
            if ("areCompatible".equals(method.name) && ("(L" + TARGET + ";)Z").equals(method.desc)) {
                if (compatible != null || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    throw new IllegalStateException("Capability comparison method changed");
                }
                compatible = method;
            }
        }
        if (compatible == null) throw new IllegalStateException("Capability comparison missing");

        MethodInsnNode equality = null;
        int serializations = 0;
        for (AbstractInsnNode instruction : compatible.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (serialization(call)) serializations++;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && COMPOUND.equals(call.owner)
                && "equals".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                if (equality != null) throw new IllegalStateException("Multiple capability NBT comparisons");
                equality = call;
            }
        }
        if (equality == null || serializations != 2) {
            throw new IllegalStateException("Expected two capability serializations and one NBT comparison");
        }
        // Keep all writers on the left, then all writers on the right, before any
        // equality check. Providers can mutate state, throw, or be injected later.
        AbstractInsnNode rightSerialize = previous(equality);
        AbstractInsnNode rightReceiver = previous(rightSerialize);
        AbstractInsnNode leftSerialize = previous(rightReceiver);
        AbstractInsnNode leftReceiver = previous(leftSerialize);
        if (!serialization(rightSerialize) || !load(rightReceiver, 1)
            || !serialization(leftSerialize) || !load(leftReceiver, 0)) {
            throw new IllegalStateException("Capability serialization order changed");
        }
        compatible.instructions.set(equality, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "equal",
            "(L" + COMPOUND + ";Ljava/lang/Object;)Z", false));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean serialization(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && TARGET.equals(call.owner)
            && "serializeNBT".equals(call.name) && ("()L" + COMPOUND + ";").equals(call.desc);
    }

    private static boolean load(AbstractInsnNode instruction, int variable) {
        return instruction instanceof VarInsnNode && instruction.getOpcode() == Opcodes.ALOAD
            && ((VarInsnNode) instruction).var == variable;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
