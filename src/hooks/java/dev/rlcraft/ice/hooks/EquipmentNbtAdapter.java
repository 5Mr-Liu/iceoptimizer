package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Changes only the compound-equality calls inside vanilla ItemStack equality. */
final class EquipmentNbtAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/item/ItemStack";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/EquipmentNbtBridge";
    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("ItemStack equality target changed");
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!"func_77970_a".equals(method.name) && !"func_77959_d".equals(method.name)
                && !"areItemStackTagsEqual".equals(method.name) && !"isItemStackEqual".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && "net/minecraft/nbt/NBTTagCompound".equals(call.owner)
                    && "equals".equals(call.name) && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                        "equal", "(Lnet/minecraft/nbt/NBTTagCompound;Ljava/lang/Object;)Z", false));
                    changed++;
                }
            }
        }
        if (changed != 2) throw new IllegalStateException("Expected two vanilla ItemStack compound comparisons, got " + changed);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
