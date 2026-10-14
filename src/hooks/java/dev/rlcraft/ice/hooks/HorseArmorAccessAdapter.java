package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

final class HorseArmorAccessAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/entity/passive/AbstractHorse";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/entity/HorseArmorAccess";
    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name) || node.interfaces.contains(ACCESS)) throw new IllegalStateException("Horse inventory ABI changed");
        FieldNode inventory = null;
        for (FieldNode field : node.fields) if ("Lnet/minecraft/inventory/ContainerHorseChest;".equals(field.desc)
            && ("field_110296_bG".equals(field.name) || "horseChest".equals(field.name))) inventory = field;
        if (inventory == null) throw new IllegalStateException("Horse inventory field missing");
        node.interfaces.add(ACCESS);
        MethodNode accessor = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$horseArmor", "()Lnet/minecraft/item/ItemStack;", null, null);
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        accessor.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, inventory.name, inventory.desc));
        accessor.instructions.add(new InsnNode(Opcodes.ICONST_1));
        accessor.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/inventory/ContainerHorseChest",
            "horseChest".equals(inventory.name) ? "getStackInSlot" : "func_70301_a", "(I)Lnet/minecraft/item/ItemStack;", false));
        accessor.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(accessor);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
