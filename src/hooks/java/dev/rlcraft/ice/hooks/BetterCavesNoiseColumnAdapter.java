package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Stores the normal 0..255 Better Caves noise column in a contiguous array. */
final class BetterCavesNoiseColumnAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/bettercaves/BetterCavesOptimizationBridge";
    static final String VALUES_FIELD = "ice$values";
    static final String MAP_MODE_FIELD = "ice$mapMode";
    static final String COPY_METHOD = "ice$copy";
    private static final String MAP_FIELD = "columnValues";
    private static final String MIN_FIELD = "min";
    private static final String MAX_FIELD = "max";
    private static final String TUPLE =
        "com/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple";
    private static final String TUPLE_DESC = "L" + TUPLE + ";";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Better Caves NoiseColumn 类名变化：" + node.name);
        }
        validateFields(node);
        MethodNode constructor = find(node, "<init>", "()V");
        MethodNode put = find(node, "put", "(I" + TUPLE_DESC + ")V");
        MethodNode get = find(node, "get", "(I)" + TUPLE_DESC);
        MethodNode values = find(node, "getColumnValues", "()Ljava/util/Map;");
        rejectMethod(node, "ice$materialize");
        rejectMethod(node, "ice$checkIndex");
        rejectMethod(node, COPY_METHOD);
        requireCalls(constructor, "java/util/HashMap", "<init>", "()V", 1);
        requireCalls(put, "java/util/Map", "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 1);
        requireCalls(get, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", 1);

        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            VALUES_FIELD, "[" + TUPLE_DESC, null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            MAP_MODE_FIELD, "Z", null, null));
        rewriteConstructor(node.name, constructor);
        rewritePut(node.name, put);
        rewriteGet(node.name, get);
        rewriteValues(node.name, values);
        node.methods.add(createMaterialize(node.name));
        node.methods.add(createCheckIndex(node.name));
        node.methods.add(createCopy(node.name));

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void validateFields(ClassNode node) {
        int maps = 0;
        int mins = 0;
        int maxes = 0;
        for (FieldNode field : node.fields) {
            if (VALUES_FIELD.equals(field.name) || MAP_MODE_FIELD.equals(field.name)) {
                throw new IllegalStateException("Better Caves NoiseColumn 字段冲突：" + field.name);
            }
            if (MAP_FIELD.equals(field.name) && "Ljava/util/Map;".equals(field.desc)) maps++;
            if (MIN_FIELD.equals(field.name) && "I".equals(field.desc)) mins++;
            if (MAX_FIELD.equals(field.name) && "I".equals(field.desc)) maxes++;
        }
        if (maps != 1 || mins != 1 || maxes != 1) {
            throw new IllegalStateException("Better Caves NoiseColumn 字段结构变化：map="
                + maps + ", min=" + mins + ", max=" + maxes);
        }
    }

    private static void rewriteConstructor(String owner, MethodNode method) {
        reset(method, 1);
        LabelNode original = new LabelNode();
        LabelNode initialized = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
        insn.add(new JumpInsnNode(Opcodes.IFEQ, original));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        insn.add(new TypeInsnNode(Opcodes.ANEWARRAY, TUPLE));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, VALUES_FIELD, "[" + TUPLE_DESC));
        insn.add(new JumpInsnNode(Opcodes.GOTO, initialized));
        insn.add(original);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(initialized);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new LdcInsnNode(Integer.valueOf(Integer.MAX_VALUE)));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MIN_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new LdcInsnNode(Integer.valueOf(Integer.MIN_VALUE)));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MAX_FIELD, "I"));
        insn.add(new InsnNode(Opcodes.RETURN));
    }

    private static void rewritePut(String owner, MethodNode method) {
        reset(method, 3);
        LabelNode mapPath = new LabelNode();
        LabelNode updateBounds = new LabelNode();
        LabelNode skipMin = new LabelNode();
        LabelNode skipMax = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[" + TUPLE_DESC));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, mapPath));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_MODE_FIELD, "Z"));
        insn.add(new JumpInsnNode(Opcodes.IFNE, mapPath));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new JumpInsnNode(Opcodes.IFLT, mapPath));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, mapPath));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[" + TUPLE_DESC));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new InsnNode(Opcodes.AASTORE));
        insn.add(new JumpInsnNode(Opcodes.GOTO, updateBounds));

        insn.add(mapPath);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        LabelNode mapReady = new LabelNode();
        insn.add(new JumpInsnNode(Opcodes.IFNONNULL, mapReady));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "ice$materialize", "()V", false));
        insn.add(mapReady);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
            "(I)Ljava/lang/Integer;", false));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        insn.add(new InsnNode(Opcodes.POP));

        insn.add(updateBounds);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MIN_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, skipMin));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MIN_FIELD, "I"));
        insn.add(skipMin);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAX_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPLE, skipMax));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MAX_FIELD, "I"));
        insn.add(skipMax);
        insn.add(new InsnNode(Opcodes.RETURN));
    }

    private static void rewriteGet(String owner, MethodNode method) {
        reset(method, 2);
        LabelNode mapPath = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "ice$checkIndex", "(I)V", false));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[" + TUPLE_DESC));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, mapPath));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_MODE_FIELD, "Z"));
        insn.add(new JumpInsnNode(Opcodes.IFNE, mapPath));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[" + TUPLE_DESC));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new InsnNode(Opcodes.AALOAD));
        insn.add(new InsnNode(Opcodes.ARETURN));
        insn.add(mapPath);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
            "(I)Ljava/lang/Integer;", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insn.add(new TypeInsnNode(Opcodes.CHECKCAST, TUPLE));
        insn.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void rewriteValues(String owner, MethodNode method) {
        reset(method, 1);
        LabelNode ready = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "ice$materialize", "()V", false));
        insn.add(ready);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(new InsnNode(Opcodes.ARETURN));
    }

    private static MethodNode createMaterialize(String owner) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "ice$materialize", "()V", null, null);
        method.maxLocals = 4;
        LabelNode create = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode fillDone = new LabelNode();
        LabelNode finish = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, create));
        insn.add(new InsnNode(Opcodes.RETURN));
        insn.add(create);
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        insn.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MIN_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAX_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGT, fillDone));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MIN_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new JumpInsnNode(Opcodes.IFLT, next));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, next));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[" + TUPLE_DESC));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new InsnNode(Opcodes.AALOAD));
        insn.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, next));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
            "(I)Ljava/lang/Integer;", false));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        insn.add(new InsnNode(Opcodes.POP));
        insn.add(next);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAX_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, fillDone));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(2, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(fillDone);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MAP_FIELD, "Ljava/util/Map;"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new InsnNode(Opcodes.ICONST_1));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, MAP_MODE_FIELD, "Z"));
        insn.add(finish);
        insn.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodNode createCheckIndex(String owner) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "ice$checkIndex", "(I)V", null, null);
        method.maxLocals = 2;
        LabelNode invalid = new LabelNode();
        LabelNode valid = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MIN_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPLT, invalid));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAX_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPLE, valid));
        insn.add(invalid);
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IndexOutOfBoundsException"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        insn.add(new LdcInsnNode("No corresponding noise value in NoiseColumn for y-value: "));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(I)Ljava/lang/StringBuilder;", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IndexOutOfBoundsException",
            "<init>", "(Ljava/lang/String;)V", false));
        insn.add(new InsnNode(Opcodes.ATHROW));
        insn.add(valid);
        insn.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodNode createCopy(String owner) {
        String column = "L" + owner + ";";
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, COPY_METHOD, "()" + column, null, null);
        method.maxLocals = 4;
        LabelNode empty = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new TypeInsnNode(Opcodes.NEW, owner));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false));
        insn.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MIN_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAX_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGT, empty));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MIN_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)" + TUPLE_DESC, false));
        insn.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, next));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TUPLE,
            BetterCavesNoiseTupleAdapter.COPY_METHOD, "()" + TUPLE_DESC, false));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "put",
            "(I" + TUPLE_DESC + ")V", false));
        insn.add(next);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, MAX_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, done));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(2, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(done);
        insn.add(empty);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static void reset(MethodNode method, int maxLocals) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxLocals = maxLocals;
        method.maxStack = 0;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                match = method;
                count++;
            }
        }
        if (count != 1 || match == null) {
            throw new IllegalStateException("Better Caves NoiseColumn " + name + descriptor
                + " 匹配数量应为 1，实际 " + count);
        }
        return match;
    }

    private static void rejectMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name)) {
                throw new IllegalStateException("Better Caves NoiseColumn 方法冲突：" + name);
            }
        }
    }

    private static void requireCalls(MethodNode method, String owner, String name,
                                     String descriptor, int expected) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        if (count != expected) {
            throw new IllegalStateException("Better Caves NoiseColumn 调用图变化：" + method.name
                + " -> " + owner + '.' + name + descriptor + "=" + count);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
