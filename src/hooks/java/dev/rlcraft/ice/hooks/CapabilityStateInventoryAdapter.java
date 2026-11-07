package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reuses Slot/Size leaves and preserves the writer's original mutable-container ownership. */
final class CapabilityStateInventoryAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraftforge/items/ItemStackHandler";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateBridge";
    static final String HELPER = "rlcraftIce$serializeStateSnapshot";
    private static final String DESCRIPTOR = "()Lnet/minecraft/nbt/NBTTagCompound;";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Inventory snapshot class changed");
        MethodNode serialize = null;
        for (MethodNode method : node.methods) {
            if (HELPER.equals(method.name)) throw new IllegalStateException("Inventory snapshot helper already exists");
            if ("serializeNBT".equals(method.name) && DESCRIPTOR.equals(method.desc)) serialize = method;
        }
        if (serialize == null) throw new IllegalStateException("Inventory snapshot writer missing");
        MethodNode copy = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            HELPER, DESCRIPTOR, null, null);
        serialize.accept(copy);
        int writes = 0;
        for (AbstractInsnNode instruction : copy.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("net/minecraft/nbt/NBTTagCompound".equals(call.owner)
                && ("setInteger".equals(call.name) || "func_74768_a".equals(call.name))
                && "(Ljava/lang/String;I)V".equals(call.desc)) {
                copy.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                    "writeInventoryInteger", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/String;I)V", false));
                writes++;
            }
        }
        if (writes != 2) throw new IllegalStateException("Inventory slot/size writers changed: " + writes);
        node.methods.add(copy);
        InsnList entry = new InsnList();
        LabelNode fallback = new LabelNode();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "useSnapshots", "()Z", false));
        entry.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, HELPER, DESCRIPTOR, false));
        entry.add(new InsnNode(Opcodes.ARETURN));
        entry.add(fallback);
        serialize.instructions.insert(entry);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }
}
