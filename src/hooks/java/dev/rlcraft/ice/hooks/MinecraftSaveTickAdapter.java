package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Installs collections which update ordered chunk buckets at the actual mutation. */
final class MinecraftSaveTickAdapter implements OptimizerBytecodeAdapter {
    static final String WORLD_TARGET = "net/minecraft/world/WorldServer";
    static final String PROVIDER_TARGET = "net/minecraft/world/gen/ChunkProviderServer";
    static final String ACCESSOR = "dev/rlcraft/ice/optimizer/compat/save/PendingTickAccessor";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/save/SaveTickIndexBridge";
    static final String PENDING_METHOD = "func_72920_a";
    static final String PENDING_DESCRIPTOR = "(Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;";
    static final String ORIGINAL_PENDING_METHOD = "ice$originalPendingBlockUpdates";
    static final String TREE = BRIDGE + "$TickTree", LIST = BRIDGE + "$TickList";

    public byte[] transform(String transformedName, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!WORLD_TARGET.equals(node.name)) throw new IllegalStateException(node.name);
        if (node.interfaces.contains(ACCESSOR)) throw new IllegalStateException("duplicate pending tick adapter");
        MethodNode pending = null;
        int trees = 0, lists = 0;
        for (MethodNode method : node.methods) {
            if (PENDING_METHOD.equals(method.name) && PENDING_DESCRIPTOR.equals(method.desc)) pending = method;
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode in : method.instructions.toArray()) {
                if (!(in instanceof FieldInsnNode) || in.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode field = (FieldInsnNode) in;
                if (!WORLD_TARGET.equals(field.owner)) continue;
                AbstractInsnNode before = previous(in);
                if (!(before instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) before;
                if ("field_73065_O".equals(field.name) && "java/util/TreeSet".equals(call.owner)
                    && "<init>".equals(call.name) && "()V".equals(call.desc)) {
                    AbstractInsnNode allocation = previous(previous(call));
                    if (!(allocation instanceof TypeInsnNode) || allocation.getOpcode() != Opcodes.NEW)
                        throw new IllegalStateException("pending tree allocation changed");
                    ((TypeInsnNode) allocation).desc = TREE; call.owner = TREE; trees++;
                } else if ("field_94579_S".equals(field.name)
                    && "com/google/common/collect/Lists".equals(call.owner)
                    && "newArrayList".equals(call.name) && "()Ljava/util/ArrayList;".equals(call.desc)) {
                    InsnList code = new InsnList();
                    code.add(new TypeInsnNode(Opcodes.NEW, LIST)); code.add(new InsnNode(Opcodes.DUP));
                    code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LIST, "<init>", "()V", false));
                    method.instructions.insertBefore(call, code); method.instructions.remove(call); lists++;
                }
            }
        }
        if (pending == null || trees != 1 || lists != 1) throw new IllegalStateException("pending tick layout changed " + trees + "/" + lists);
        node.interfaces.add(ACCESSOR);
        pending.name = ORIGINAL_PENDING_METHOD; pending.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC, PENDING_METHOD, PENDING_DESCRIPTOR, null, null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "pendingBlockUpdates",
            "(L" + ACCESSOR + ";Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;", false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(wrapper);
        accessor(node, "ice$pendingTickTree", "()Ljava/lang/Iterable;", "field_73065_O", "Ljava/util/TreeSet;");
        accessor(node, "ice$pendingTicksThisTick", "()Ljava/util/List;", "field_94579_S", "Ljava/util/List;");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
    private static AbstractInsnNode previous(AbstractInsnNode in) {
        do { in = in.getPrevious(); } while (in != null && in.getOpcode() < 0);
        return in;
    }
    private static void accessor(ClassNode node, String name, String desc, String field, String type) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD_TARGET, field, type));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
    }
}
