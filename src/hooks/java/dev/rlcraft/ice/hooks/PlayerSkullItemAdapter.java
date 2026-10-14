package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Applies the existing policy before item rendering can perform synchronous profile I/O. */
final class PlayerSkullItemAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/renderer/tileentity/TileEntityItemStackRenderer";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/skull/SkullProfileBridge";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!("func_192838_a".equals(method.name) || "renderByItem".equals(method.name))
                || !"(Lnet/minecraft/item/ItemStack;F)V".equals(method.desc)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && "net/minecraft/tileentity/TileEntitySkull".equals(call.owner)
                    && ("func_174884_b".equals(call.name) || "updateGameProfile".equals(call.name))
                    && "(Lcom/mojang/authlib/GameProfile;)Lcom/mojang/authlib/GameProfile;".equals(call.desc)) {
                    call.owner = BRIDGE;
                    call.name = "resolveForRenderLookup";
                    changed++;
                }
            }
        }
        if (!TARGET.equals(node.name) || changed != 1) {
            throw new IllegalStateException("Item skull profile call changed: " + node.name + "/" + changed);
        }
        // The call has the identical signature: SkullOwner conversion/write-back is untouched.
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
