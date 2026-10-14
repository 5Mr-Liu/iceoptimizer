package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Targets only private regeneration metadata and matching, never fish population/RNG updates. */
final class FishingBiomeAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/theawesomegem/fishingmadebetter/common/capability/world/ChunkFishingData";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/fishing/FishingBiomeBridge";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (FieldNode field : node.fields) if ("byName".equals(field.name)) field.access &= ~Opcodes.ACC_FINAL;
        MethodNode regenerate = ServerModAdapterSupport.require(node, "regenerateInitialFishes", "()V");
        MethodNode fastRegen = ServerModAdapterSupport.copy(regenerate, "ice$fast$regenerateInitialFishes");
        ServerModAdapterSupport.route(node, regenerate, fastRegen, BRIDGE, "isEnabled", true);
        MethodNode init = ServerModAdapterSupport.require(node, "initByName", "()V");
        MethodNode fastInit = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            "ice$fast$initByName", "()V", null, null);
        fastInit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastInit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "sharedNames", "()Ljava/util/Map;", false));
        fastInit.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET, "byName", "Ljava/util/Map;"));
        fastInit.instructions.add(new InsnNode(Opcodes.RETURN));
        ServerModAdapterSupport.route(node, init, fastInit, BRIDGE, "isEnabled", false);
        MethodNode matches = ServerModAdapterSupport.require(node, "isChunkBiomeInBiomeList", "(Ljava/util/List;)Z");
        MethodNode fast = ServerModAdapterSupport.copy(matches, "ice$fast$isChunkBiomeInBiomeList");
        MethodInsnNode getPos = null;
        for (AbstractInsnNode instruction : fast.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && "func_76632_l".equals(((MethodInsnNode) instruction).name)) {
                getPos = (MethodInsnNode) instruction; break;
            }
        }
        if (getPos == null) throw new IllegalStateException("Fishing biome matching shape changed");
        // Keep null/empty/lazy-initialization prefix, replace from chunk receiver for getPos onward.
        AbstractInsnNode start = getPos.getPrevious().getPrevious();
        while (start.getOpcode() < 0) start = start.getPrevious();
        if (!(start instanceof VarInsnNode) || start.getOpcode() != Opcodes.ALOAD) throw new IllegalStateException("Fishing chunk receiver changed");
        for (AbstractInsnNode instruction = start; instruction != null;) {
            AbstractInsnNode next = instruction.getNext(); fast.instructions.remove(instruction); instruction = next;
        }
        fast.localVariables = null;
        fast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "chunk", "Lnet/minecraft/world/chunk/Chunk;"));
        fast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "world", "Lnet/minecraft/world/World;"));
        fast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "byName", "Ljava/util/Map;"));
        fast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "matches",
            "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;Ljava/util/Map;Ljava/util/List;)Z", false));
        fast.instructions.add(new InsnNode(Opcodes.IRETURN));
        ServerModAdapterSupport.route(node, matches, fast, BRIDGE, "isEnabled", false);
        return ServerModAdapterSupport.write(reader, node);
    }
}
