package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces SRPMixins' iterator filter with a fail-open compiled bridge. */
final class SrpSpawnFilterAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "srpmixins/handlers/SpawnPotentialsHandler";
    static final String WRAPPER = TARGET + "$BiomeSpawnListEntryWrapper";
    static final String ENTRY = "net/minecraft/world/biome/Biome$SpawnListEntry";
    static final String SAVE_DATA =
        "com/dhanantry/scapeandrunparasites/world/SRPSaveData";
    static final String WORLD_DATA =
        "com/dhanantry/scapeandrunparasites/world/SRPWorldData";
    static final String CALLBACKS =
        "dev/rlcraft/ice/optimizer/compat/srp/SrpSpawnFilterCallbacks";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/srp/SrpSpawnFilterBridge";
    static final String FILTER_METHOD = "filterSpawnEntries";
    static final String FILTER_DESCRIPTOR =
        "(Ljava/util/List;L" + SAVE_DATA + ";L" + WORLD_DATA + ";ZI)Ljava/util/List;";
    static final String ORIGINAL_FILTER = "ice$originalFilterSpawnEntries";
    static final String CALLBACK_FIELD = "ice$spawnFilterCallbacks";
    static final String BRIDGE_DESCRIPTOR =
        "(L" + CALLBACKS + ";Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;ZI)Ljava/util/List;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("SRPMixins 刷怪过滤目标类变化：" + node.name);
        }
        if (node.interfaces.contains(CALLBACKS)) {
            throw new IllegalStateException("SRPMixins 刷怪过滤回调已安装");
        }
        rejectField(node, CALLBACK_FIELD);
        rejectMethod(node, ORIGINAL_FILTER, FILTER_DESCRIPTOR);
        MethodNode filter = requireMethod(node, FILTER_METHOD, FILTER_DESCRIPTOR);
        MethodNode colony = requireMethod(node, "isColonyLocked",
            "(IL" + WORLD_DATA + ";Z)Z");
        MethodNode subCap = requireMethod(node, "isSubCapLocked",
            "(Ljava/lang/Class;I)Z");
        MethodNode reset = requireMethod(node, "resetCaches", "()V");
        MethodNode constructor = requireMethod(node, "<init>", "()V");
        MethodNode clinit = requireMethod(node, "<clinit>", "()V");
        validateFilter(filter);
        validateReadOnlyHelper(colony);
        validateReadOnlyHelper(subCap);

        node.interfaces.add(CALLBACKS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
            | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            CALLBACK_FIELD, "L" + CALLBACKS + ";", null, null));
        addCallbackInitialization(clinit);
        addCallbackMethods(node);
        wrapFilter(node, filter);
        InsnList invalidate = new InsnList();
        invalidate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            BRIDGE, "invalidate", "()V", false));
        reset.instructions.insert(invalidate);

        if ((constructor.access & Opcodes.ACC_PUBLIC) == 0) {
            throw new IllegalStateException("SRPMixins handler 构造器不再可用于静态回调实例");
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void validateFilter(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("SRPMixins filterSpawnEntries 不再是 static");
        }
        int iterators = countCalls(method, Opcodes.INVOKEINTERFACE,
            "java/util/List", "iterator", "()Ljava/util/Iterator;");
        int next = countCalls(method, Opcodes.INVOKEINTERFACE,
            "java/util/Iterator", "next", "()Ljava/lang/Object;");
        int parasiteChecks = countCalls(method, Opcodes.INVOKEVIRTUAL,
            SAVE_DATA, "checkParasiteID", "(I)Z");
        int colonyChecks = countCalls(method, Opcodes.INVOKESTATIC,
            TARGET, "isColonyLocked", "(IL" + WORLD_DATA + ";Z)Z");
        int subCapChecks = countCalls(method, Opcodes.INVOKESTATIC,
            TARGET, "isSubCapLocked", "(Ljava/lang/Class;I)Z");
        int additions = countCalls(method, Opcodes.INVOKEINTERFACE,
            "java/util/List", "add", "(Ljava/lang/Object;)Z");
        int wrapperIds = countFields(method, Opcodes.GETFIELD, WRAPPER, "paraId", "I");
        int wrapperEntries = countFields(method, Opcodes.GETFIELD, WRAPPER,
            "entry", "L" + ENTRY + ";");
        int arrays = countTypes(method, Opcodes.NEW, "java/util/ArrayList");
        if (iterators != 1 || next != 1 || parasiteChecks != 1 || colonyChecks != 1
            || subCapChecks != 1 || additions != 1 || wrapperIds != 3
            || wrapperEntries != 2 || arrays != 1) {
            throw new IllegalStateException("SRPMixins filterSpawnEntries 调用图变化：iterator/next="
                + iterators + '/' + next + ", checks=" + parasiteChecks + '/'
                + colonyChecks + '/' + subCapChecks + ", add=" + additions
                + ", wrapper=" + wrapperIds + '/' + wrapperEntries + ", list=" + arrays);
        }
    }

    private static void validateReadOnlyHelper(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
                || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                throw new IllegalStateException("SRPMixins 过滤 helper 出现可变写入："
                    + method.name + method.desc);
            }
        }
    }

    private static void addCallbackInitialization(MethodNode clinit) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, TARGET));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, "<init>", "()V", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET,
            CALLBACK_FIELD, "L" + CALLBACKS + ";"));
        clinit.instructions.insert(code);
    }

    private static void addCallbackMethods(ClassNode node) {
        addWrapperIntAccessor(node, "ice$spawnParaId", "paraId");
        addWrapperEntryAccessor(node);
        addEntityClassAccessor(node);
        addCheckCallback(node);
        addColonyCallback(node);
        addSubCapCallback(node);
    }

    private static void addWrapperIntAccessor(ClassNode node, String name, String field) {
        MethodNode method = callbackMethod(name, "(Ljava/lang/Object;)I");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, WRAPPER));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, WRAPPER, field, "I"));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static void addWrapperEntryAccessor(ClassNode node) {
        MethodNode method = callbackMethod("ice$spawnEntry", "(Ljava/lang/Object;)Ljava/lang/Object;");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, WRAPPER));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, WRAPPER,
            "entry", "L" + ENTRY + ";"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
    }

    private static void addEntityClassAccessor(ClassNode node) {
        MethodNode method = callbackMethod("ice$spawnEntityClass",
            "(Ljava/lang/Object;)Ljava/lang/Class;");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTRY));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ENTRY,
            "field_76300_b", "Ljava/lang/Class;"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
    }

    private static void addCheckCallback(ClassNode node) {
        MethodNode method = callbackMethod("ice$spawnCheckParasiteId", "(Ljava/lang/Object;I)Z");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, SAVE_DATA));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            SAVE_DATA, "checkParasiteID", "(I)Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static void addColonyCallback(ClassNode node) {
        MethodNode method = callbackMethod("ice$spawnColonyLocked", "(ILjava/lang/Object;Z)Z");
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, WORLD_DATA));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            TARGET, "isColonyLocked", "(IL" + WORLD_DATA + ";Z)Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static void addSubCapCallback(ClassNode node) {
        MethodNode method = callbackMethod("ice$spawnSubCapLocked", "(Ljava/lang/Class;I)Z");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            TARGET, "isSubCapLocked", "(Ljava/lang/Class;I)Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static MethodNode callbackMethod(String name, String descriptor) {
        return new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
    }

    private static void wrapFilter(ClassNode node, MethodNode original) {
        int access = original.access;
        String signature = original.signature;
        String[] exceptions = exceptions(original);
        original.name = ORIGINAL_FILTER;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access,
            FILTER_METHOD, FILTER_DESCRIPTOR, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET,
            CALLBACK_FIELD, "L" + CALLBACKS + ";"));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            BRIDGE, "tryFilter", BRIDGE_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.DUP));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new InsnNode(Opcodes.POP));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            TARGET, ORIGINAL_FILTER, FILTER_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(wrapper);
    }

    private static int countCalls(MethodNode method, int opcode, String owner,
                                  String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == opcode && owner.equals(call.owner)
                && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countFields(MethodNode method, int opcode, String owner,
                                   String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.getOpcode() == opcode && owner.equals(field.owner)
                && name.equals(field.name) && descriptor.equals(field.desc)) count++;
        }
        return count;
    }

    private static int countTypes(MethodNode method, int opcode, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == opcode
                && type.equals(((TypeInsnNode) instruction).desc)) count++;
        }
        return count;
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
        if (count != 1) throw new IllegalStateException("SRPMixins 方法数量变化："
            + name + descriptor + '=' + count);
        return result;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException("SRPMixins 已存在 ICE 方法 " + name);
            }
        }
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) {
                throw new IllegalStateException("SRPMixins 已存在 ICE 字段 " + name);
            }
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
