package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Reads the exact fields used by ItemStack.writeToNBT without serializing a stack. */
final class CapabilityStateStackAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/item/ItemStack";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/nbt/CapabilityStateStackAccess";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name) || node.interfaces.contains(ACCESS)) throw new IllegalStateException("ItemStack state access changed");
        node.interfaces.add(ACCESS);
        getter(node, "ice$stateItem", "Lnet/minecraft/item/Item;", "item", "field_151002_e");
        getter(node, "ice$stateCount", "I", "stackSize", "field_77994_a");
        getter(node, "ice$stateDamage", "I", "itemDamage", "field_77991_e");
        getter(node, "ice$stateCapabilities", "Lnet/minecraftforge/common/capabilities/CapabilityDispatcher;", "capabilities", "capabilities");
        getter(node, "ice$statePendingNbt", "Lnet/minecraft/nbt/NBTTagCompound;", "capNBT", "capNBT");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void getter(ClassNode node, String method, String descriptor, String mapped, String srg) {
        String field = null;
        for (FieldNode candidate : node.fields) {
            if (descriptor.equals(candidate.desc) && (mapped.equals(candidate.name) || srg.equals(candidate.name))) field = candidate.name;
        }
        if (field == null) throw new IllegalStateException("ItemStack field missing: " + mapped);
        String result = "ice$stateCapabilities".equals(method) ? "Ljava/lang/Object;" : descriptor;
        MethodNode code = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
            method, "()" + result, null, null);
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, node.name, field, descriptor);
        code.visitInsn(Type.getType(descriptor).getOpcode(Opcodes.IRETURN));
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(code);
    }
}
