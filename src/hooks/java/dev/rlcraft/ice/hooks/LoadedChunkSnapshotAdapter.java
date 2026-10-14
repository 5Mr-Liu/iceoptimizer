package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Hooks the collection owner, so external map and view mutations invalidate snapshots too. */
final class LoadedChunkSnapshotAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/world/gen/ChunkProviderServer";
    static final String MAP = "it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap";
    static final String TRACKED = "dev/rlcraft/ice/optimizer/compat/events/TrackedLoadedChunkMap";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int allocations = 0, constructors = 0;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                    && MAP.equals(((TypeInsnNode) instruction).desc)) {
                    ((TypeInsnNode) instruction).desc = TRACKED; allocations++;
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (MAP.equals(call.owner) && "<init>".equals(call.name)
                        && ("()V".equals(call.desc) || "(I)V".equals(call.desc))) {
                        call.owner = TRACKED; constructors++;
                    }
                }
            }
        }
        if (allocations != 1 || constructors != 1) throw new IllegalStateException("Loaded chunk map construction changed");
        // Allocation owner changes only; retain every original control-flow frame verbatim.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
