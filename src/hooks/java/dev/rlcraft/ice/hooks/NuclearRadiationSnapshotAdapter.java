package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Reuses only invocation-owned arrays; collection iteration and RNG remain original. */
final class NuclearRadiationSnapshotAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "nc/radiation/RadiationHandler";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/events/ServerSnapshotBridge";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode method = ServerModAdapterSupport.require(node, "updateWorldRadiation",
            "(Lnet/minecraftforge/fml/common/gameevent/TickEvent$WorldTickEvent;)V");
        MethodNode fast = ServerModAdapterSupport.copy(method, "ice$fast$updateWorldRadiation");
        int count = 0;
        for (AbstractInsnNode instruction : fast.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!"toArray".equals(call.name) || !"([Ljava/lang/Object;)[Ljava/lang/Object;".equals(call.desc)) continue;
            AbstractInsnNode allocation = call.getPrevious();
            while (allocation.getOpcode() < 0) allocation = allocation.getPrevious();
            if (!(allocation instanceof TypeInsnNode) || allocation.getOpcode() != Opcodes.ANEWARRAY) {
                throw new IllegalStateException("NuclearCraft snapshot array shape changed");
            }
            String component = ((TypeInsnNode) allocation).desc;
            InsnList replacement = new InsnList();
            replacement.add(new LdcInsnNode(Type.getObjectType(component)));
            replacement.add(new LdcInsnNode(count++));
            fast.instructions.insertBefore(allocation, replacement);
            fast.instructions.remove(allocation);
            fast.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "snapshot",
                "(Ljava/util/Collection;ILjava/lang/Class;I)[Ljava/lang/Object;", false));
        }
        if (count != 3) throw new IllegalStateException("NuclearCraft snapshot count changed: " + count);
        ServerModAdapterSupport.route(node, method, fast, BRIDGE, "radiationEnabled", true);
        return ServerModAdapterSupport.write(reader, node);
    }
}
