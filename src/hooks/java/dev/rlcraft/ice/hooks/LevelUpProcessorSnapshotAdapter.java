package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Retains the original synchronized selection phase, followed by processor callbacks. */
final class LevelUpProcessorSnapshotAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "levelup2/skills/crafting/FurnaceEfficiencyBonus";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/events/ServerSnapshotBridge";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/events/ServerProcessorAccess";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode method = ServerModAdapterSupport.require(node, "doFurnaceTicks",
            "(Lnet/minecraftforge/fml/common/gameevent/TickEvent$WorldTickEvent;)V");
        MethodNode fast = ServerModAdapterSupport.copy(method, "ice$fast$doFurnaceTicks");
        MethodInsnNode stream = null, collect = null;
        for (AbstractInsnNode instruction : fast.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("java/util/List".equals(call.owner) && "stream".equals(call.name)) stream = call;
            if ("java/util/stream/Stream".equals(call.owner) && "collect".equals(call.name)) collect = call;
        }
        if (stream == null || collect == null) throw new IllegalStateException("LevelUp processor collection changed");
        ServerModAdapterSupport.require(node, "lambda$doFurnaceTicks$0", "(Lnet/minecraft/tileentity/TileEntity;)Z");
        ServerModAdapterSupport.require(node, "lambda$doFurnaceTicks$1", "(Lnet/minecraft/tileentity/TileEntity;)Llevelup2/api/IProcessor;");
        node.interfaces.add(ACCESS);
        addCallback(node, "ice$hasProcessor", "Z", "lambda$doFurnaceTicks$0", "Z");
        addCallback(node, "ice$getProcessor", "Ljava/lang/Object;", "lambda$doFurnaceTicks$1", "Llevelup2/api/IProcessor;");
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "processors",
            "(Ljava/util/List;L" + ACCESS + ";)Ljava/util/List;", false));
        fast.instructions.insertBefore(stream, replacement);
        AbstractInsnNode after = collect.getNext();
        for (AbstractInsnNode at = stream; at != after;) {
            AbstractInsnNode next = at.getNext(); fast.instructions.remove(at); at = next;
        }
        ServerModAdapterSupport.route(node, method, fast, BRIDGE, "levelUpEnabled", true);
        return ServerModAdapterSupport.write(reader, node);
    }
    private static void addCallback(ClassNode node, String name, String result, String original, String originalResult) {
        MethodNode method = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, "(Ljava/lang/Object;)" + result, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/tileentity/TileEntity"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, original,
            "(Lnet/minecraft/tileentity/TileEntity;)" + originalResult, false));
        method.instructions.add(new InsnNode("Z".equals(result) ? Opcodes.IRETURN : Opcodes.ARETURN));
        node.methods.add(method);
    }
}
