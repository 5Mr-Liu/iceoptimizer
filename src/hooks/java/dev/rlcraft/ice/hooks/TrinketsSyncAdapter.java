package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Changes only the two item-sync routing boundaries; packet encoding stays in Trinkets. */
final class TrinketsSyncAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "xzeroair/trinkets/network/NetworkHandler";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/trinkets/TrinketsSyncBridge";
    private static final String WRAPPER = "net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper";
    private static final String MESSAGE = "Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Trinkets network target changed");
        int players = 0, tracking = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !WRAPPER.equals(call.owner)) continue;
                boolean player = "sendTo".equals(method.name) && "sendTo".equals(call.name)
                    && ("(" + MESSAGE + "Lnet/minecraft/entity/player/EntityPlayerMP;)V").equals(call.desc);
                boolean watchers = "sendToTracking".equals(method.name) && "sendToAllTracking".equals(call.name)
                    && ("(" + MESSAGE + "Lnet/minecraft/entity/Entity;)V").equals(call.desc);
                if (!player && !watchers) continue;
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = player ? "sendTo" : "sendToTracking";
                call.desc = "(L" + WRAPPER + ";" + call.desc.substring(1);
                if (player) players++; else tracking++;
            }
        }
        if (players != 1 || tracking != 1) throw new IllegalStateException("Trinkets send boundaries changed");
        ClassWriter output = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(output);
        return output.toByteArray();
    }
}
