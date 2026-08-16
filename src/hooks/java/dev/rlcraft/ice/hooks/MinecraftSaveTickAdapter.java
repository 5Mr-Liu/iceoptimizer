package dev.rlcraft.ice.hooks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Installs the mutation-versioned scheduled-tick index around vanilla full saves. */
final class MinecraftSaveTickAdapter implements OptimizerBytecodeAdapter {
    static final String WORLD_TARGET = "net/minecraft/world/WorldServer";
    static final String PROVIDER_TARGET = "net/minecraft/world/gen/ChunkProviderServer";
    static final String ACCESSOR =
        "dev/rlcraft/ice/optimizer/compat/save/PendingTickAccessor";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/save/SaveTickIndexBridge";

    static final String PENDING_METHOD = "func_72920_a";
    static final String PENDING_DESCRIPTOR =
        "(Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;";
    static final String ORIGINAL_PENDING_METHOD = "ice$originalPendingBlockUpdates";
    static final String SAVE_METHOD = "func_186027_a";
    static final String SAVE_DESCRIPTOR = "(Z)Z";
    static final String ORIGINAL_SAVE_METHOD = "ice$original$saveChunks";
    static final String VERSION_FIELD = "ice$pendingTickVersion";

    private static final String TREE_FIELD = "field_73065_O";
    private static final String TREE_DESCRIPTOR = "Ljava/util/TreeSet;";
    private static final String CURRENT_FIELD = "field_94579_S";
    private static final String CURRENT_DESCRIPTOR = "Ljava/util/List;";
    private static final String HASH_FIELD = "field_73064_N";
    private static final String HASH_DESCRIPTOR = "Ljava/util/Set;";
    private static final String WORLD_FIELD = "field_73251_h";
    private static final String WORLD_DESCRIPTOR = "Lnet/minecraft/world/WorldServer;";

    private static final String UPDATE_TICK = "func_175654_a";
    private static final String SCHEDULE_TICK = "func_180497_b";
    private static final String TICK_UPDATES = "func_72955_a";
    private static final String BOUNDS_PENDING = "func_175712_a";
    private static final String UPDATE_DESCRIPTOR =
        "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V";
    private static final String TICK_DESCRIPTOR = "(Z)Z";
    private static final String BOUNDS_DESCRIPTOR =
        "(Lnet/minecraft/world/gen/structure/StructureBoundingBox;Z)Ljava/util/List;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (WORLD_TARGET.equals(node.name)) transformWorld(node);
        else if (PROVIDER_TARGET.equals(node.name)) transformProvider(node);
        else throw new IllegalStateException("计划刻索引目标类变化：" + node.name);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformWorld(ClassNode node) {
        if (node.interfaces.contains(ACCESSOR)) {
            throw new IllegalStateException("WorldServer 已存在 ICE 计划刻访问器");
        }
        requireField(node, HASH_FIELD, HASH_DESCRIPTOR);
        requireField(node, TREE_FIELD, TREE_DESCRIPTOR);
        requireField(node, CURRENT_FIELD, CURRENT_DESCRIPTOR);
        rejectField(node, VERSION_FIELD);

        MethodNode pending = requireMethod(node, PENDING_METHOD, PENDING_DESCRIPTOR);
        MethodNode update = requireMethod(node, UPDATE_TICK, UPDATE_DESCRIPTOR);
        MethodNode schedule = requireMethod(node, SCHEDULE_TICK, UPDATE_DESCRIPTOR);
        MethodNode tick = requireMethod(node, TICK_UPDATES, TICK_DESCRIPTOR);
        MethodNode bounds = requireMethod(node, BOUNDS_PENDING, BOUNDS_DESCRIPTOR);
        rejectMethod(node, ORIGINAL_PENDING_METHOD, PENDING_DESCRIPTOR);

        if (countCalls(pending, Opcodes.INVOKEVIRTUAL, WORLD_TARGET,
            BOUNDS_PENDING, BOUNDS_DESCRIPTOR) != 1) {
            throw new IllegalStateException("WorldServer 区块计划刻查询调用图变化");
        }
        Set<String> mutationMethods = new HashSet<String>(Arrays.asList(
            UPDATE_TICK + UPDATE_DESCRIPTOR,
            SCHEDULE_TICK + UPDATE_DESCRIPTOR,
            TICK_UPDATES + TICK_DESCRIPTOR,
            BOUNDS_PENDING + BOUNDS_DESCRIPTOR));
        validatePendingCollectionMutators(node, mutationMethods);
        requirePendingMutation(update, "updateBlockTick");
        requirePendingMutation(schedule, "scheduleBlockUpdate");
        requirePendingMutation(tick, "tickUpdates");
        requirePendingMutation(bounds, "getPendingBlockUpdates(bounds)");

        node.interfaces.add(ACCESSOR);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE
            | Opcodes.ACC_SYNTHETIC,
            VERSION_FIELD, "J", null, null));
        insertVersionBump(update);
        insertVersionBump(schedule);
        insertVersionBump(tick);
        insertVersionBump(bounds);

        int wrapperAccess = pending.access;
        String signature = pending.signature;
        String[] exceptions = exceptions(pending);
        pending.name = ORIGINAL_PENDING_METHOD;
        pending.access |= Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, wrapperAccess, PENDING_METHOD,
            PENDING_DESCRIPTOR, signature, exceptions);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "pendingBlockUpdates", "(L" + ACCESSOR
                + ";Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;", false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(wrapper);

        addReferenceAccessor(node, "ice$pendingTickTree", "()Ljava/lang/Iterable;",
            TREE_FIELD, TREE_DESCRIPTOR);
        addReferenceAccessor(node, "ice$pendingTicksThisTick", "()Ljava/util/List;",
            CURRENT_FIELD, CURRENT_DESCRIPTOR);
        MethodNode version = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$pendingTickVersion", "()J", null, null);
        version.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        version.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD_TARGET, VERSION_FIELD, "J"));
        version.instructions.add(new InsnNode(Opcodes.LRETURN));
        node.methods.add(version);
    }

    private static void transformProvider(ClassNode node) {
        requireField(node, WORLD_FIELD, WORLD_DESCRIPTOR);
        rejectMethod(node, ORIGINAL_SAVE_METHOD, SAVE_DESCRIPTOR);
        MethodNode save = requireMethod(node, SAVE_METHOD, SAVE_DESCRIPTOR);
        if (countCalls(save, Opcodes.INVOKESTATIC, "com/google/common/collect/Lists",
            "newArrayList", "(Ljava/lang/Iterable;)Ljava/util/ArrayList;") != 1
            || countCalls(save, Opcodes.INVOKESPECIAL, PROVIDER_TARGET,
                "func_73243_a", "(Lnet/minecraft/world/chunk/Chunk;)V") != 1
            || countCalls(save, Opcodes.INVOKEVIRTUAL, "net/minecraft/world/chunk/Chunk",
                "func_76601_a", "(Z)Z") != 1
            || countCalls(save, Opcodes.INVOKESPECIAL, PROVIDER_TARGET,
                "func_73242_b", "(Lnet/minecraft/world/chunk/Chunk;)V") != 1
            || countCalls(save, Opcodes.INVOKEVIRTUAL, "net/minecraft/world/chunk/Chunk",
                "func_177427_f", "(Z)V") != 1) {
            throw new IllegalStateException("ChunkProviderServer 全量保存调用图变化");
        }

        int wrapperAccess = save.access;
        String signature = save.signature;
        String[] exceptions = exceptions(save);
        save.name = ORIGINAL_SAVE_METHOD;
        save.access = (save.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, wrapperAccess, SAVE_METHOD,
            SAVE_DESCRIPTOR, signature, exceptions);
        InsnList code = wrapper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, PROVIDER_TARGET, WORLD_FIELD, WORLD_DESCRIPTOR));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(Ljava/lang/Object;Ljava/lang/Object;Z)J", false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 2));

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, PROVIDER_TARGET,
            ORIGINAL_SAVE_METHOD, SAVE_DESCRIPTOR, false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(end);
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "end", "(J)V", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.LLOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "end", "(J)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        node.methods.add(wrapper);
    }

    private static void insertVersionBump(MethodNode method) {
        InsnList bump = new InsnList();
        bump.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bump.add(new InsnNode(Opcodes.DUP));
        bump.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD_TARGET, VERSION_FIELD, "J"));
        bump.add(new InsnNode(Opcodes.LCONST_1));
        bump.add(new InsnNode(Opcodes.LADD));
        bump.add(new FieldInsnNode(Opcodes.PUTFIELD, WORLD_TARGET, VERSION_FIELD, "J"));
        method.instructions.insert(bump);
    }

    private static void addReferenceAccessor(ClassNode node, String name, String descriptor,
                                             String field, String fieldDescriptor) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD_TARGET, field, fieldDescriptor));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
    }

    private static void validatePendingCollectionMutators(ClassNode node, Set<String> allowed) {
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) || !referencesPendingField(method)) continue;
            if (containsCollectionMutation(method) && !allowed.contains(method.name + method.desc)) {
                throw new IllegalStateException("WorldServer 新增未跟踪的计划刻集合写入："
                    + method.name + method.desc);
            }
        }
    }

    private static boolean referencesPendingField(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (WORLD_TARGET.equals(field.owner) && (HASH_FIELD.equals(field.name)
                || TREE_FIELD.equals(field.name) || CURRENT_FIELD.equals(field.name))) return true;
        }
        return false;
    }

    private static boolean containsCollectionMutation(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (("add".equals(call.name) || "remove".equals(call.name) || "clear".equals(call.name))
                && (call.owner.startsWith("java/util/") || "java/lang/Iterable".equals(call.owner))) {
                return true;
            }
        }
        return false;
    }

    private static void requirePendingMutation(MethodNode method, String label) {
        if (!referencesPendingField(method) || !containsCollectionMutation(method)) {
            throw new IllegalStateException("WorldServer " + label + " 不再写入已审查计划刻集合");
        }
    }

    private static int countCalls(MethodNode method, int opcode, String owner,
                                  String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == opcode && owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) result++;
        }
        return result;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode result = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                result = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(node.name + " 方法数量变化："
            + name + descriptor + "=" + count);
        return result;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 ICE 备份方法 " + name);
            }
        }
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        int count = 0;
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) count++;
        }
        if (count != 1) throw new IllegalStateException(node.name + " 字段数量变化："
            + name + descriptor + "=" + count);
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) throw new IllegalStateException(node.name + " 已存在字段 " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static String[] exceptions(MethodNode method) {
        List<String> values = method.exceptions;
        return values == null ? null : values.toArray(new String[values.size()]);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
