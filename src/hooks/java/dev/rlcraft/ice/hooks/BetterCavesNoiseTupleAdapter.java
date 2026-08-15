package dev.rlcraft.ice.hooks;

import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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
import org.objectweb.asm.tree.FieldInsnNode;

/** Replaces Better Caves' boxed ArrayList arithmetic with an exact double array path. */
final class BetterCavesNoiseTupleAdapter implements OptimizerBytecodeAdapter {
    static final String ACCESS = "dev/rlcraft/ice/hooks/BetterCavesNoiseTupleAccess";
    static final String LIST_VIEW = "dev/rlcraft/ice/hooks/BetterCavesNoiseTupleList";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/bettercaves/BetterCavesOptimizationBridge";
    static final String VALUES_FIELD = "ice$values";
    static final String COPY_METHOD = "ice$copy";
    static final String BLEND_METHOD = "ice$blend";
    private static final String LIST_FIELD = "noiseValues";
    private static final String LENGTH_FIELD = "length";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Better Caves NoiseTuple 类名变化：" + node.name);
        }
        validateFields(node);
        if (node.interfaces.contains(ACCESS)) {
            throw new IllegalStateException("Better Caves NoiseTuple 已安装 ICE primitive ABI");
        }

        String tuple = "L" + node.name + ";";
        MethodNode constructor = find(node, "<init>", "([D)V");
        MethodNode put = find(node, "put", "(D)V");
        MethodNode get = find(node, "get", "(I)D");
        MethodNode set = find(node, "set", "(ID)V");
        MethodNode times = find(node, "times", "(F)" + tuple);
        MethodNode plus = find(node, "plus", "(" + tuple + ")" + tuple);
        MethodNode values = find(node, "getNoiseValues", "()Ljava/util/List;");
        MethodNode size = find(node, "size", "()I");
        rejectMethod(node, "ice$ensureCapacity");
        rejectMethod(node, "ice$checkIndex");
        rejectMethod(node, COPY_METHOD);
        rejectMethod(node, BLEND_METHOD);
        validateOriginalGraph(node.name, constructor, put, get, set, times, plus);

        node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            VALUES_FIELD, "[D", null, null));

        rewriteConstructor(node.name, constructor);
        rewritePut(node.name, put);
        rewriteGet(node.name, get);
        rewriteSet(node.name, set);
        rewriteTimes(node.name, times);
        rewritePlus(node.name, plus);
        rewriteValues(node.name, values);
        rewriteSize(node.name, size);
        node.methods.add(createEnsureCapacity(node.name));
        node.methods.add(createCheckIndex(node.name));
        node.methods.add(createCopy(node.name));
        node.methods.add(createBlend(node.name));

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void validateFields(ClassNode node) {
        int lists = 0;
        int lengths = 0;
        for (FieldNode field : node.fields) {
            if (VALUES_FIELD.equals(field.name)) {
                throw new IllegalStateException("Better Caves NoiseTuple 字段冲突：" + VALUES_FIELD);
            }
            if (LIST_FIELD.equals(field.name) && "Ljava/util/List;".equals(field.desc)) lists++;
            if (LENGTH_FIELD.equals(field.name) && "I".equals(field.desc)) lengths++;
        }
        if (lists != 1 || lengths != 1) {
            throw new IllegalStateException("Better Caves NoiseTuple 字段结构变化：list="
                + lists + ", length=" + lengths);
        }
    }

    private static void validateOriginalGraph(String owner, MethodNode constructor, MethodNode put,
                                              MethodNode get, MethodNode set, MethodNode times,
                                              MethodNode plus) {
        requireCalls(constructor, "java/util/ArrayList", "<init>", "()V", 1);
        requireCalls(put, "java/util/List", "add", "(Ljava/lang/Object;)Z", 1);
        requireCalls(get, "java/util/List", "get", "(I)Ljava/lang/Object;", 1);
        requireCalls(set, "java/util/List", "set", "(ILjava/lang/Object;)Ljava/lang/Object;", 1);
        requireCalls(times, owner, "put", "(D)V", 1);
        requireCalls(plus, owner, "get", "(I)D", 1);
        requireCalls(plus, owner, "put", "(D)V", 1);
    }

    private static void rewriteConstructor(String owner, MethodNode method) {
        reset(method, 4);
        LabelNode original = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
        insn.add(new JumpInsnNode(Opcodes.IFEQ, original));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insn.add(new InsnNode(Opcodes.ICONST_4));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf", "([DI)[D", false));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new TypeInsnNode(Opcodes.NEW, LIST_VIEW));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LIST_VIEW, "<init>",
            "(L" + ACCESS + ";)V", false));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, LIST_FIELD, "Ljava/util/List;"));
        insn.add(new InsnNode(Opcodes.RETURN));

        insn.add(original);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, LIST_FIELD, "Ljava/util/List;"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new InsnNode(Opcodes.DALOAD));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "put", "(D)V", false));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(2, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(done);
        insn.add(new InsnNode(Opcodes.RETURN));
    }

    private static void rewritePut(String owner, MethodNode method) {
        reset(method, 3);
        LabelNode original = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, original));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new InsnNode(Opcodes.ICONST_1));
        insn.add(new InsnNode(Opcodes.IADD));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "ice$ensureCapacity", "(I)V", false));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new VarInsnNode(Opcodes.DLOAD, 1));
        insn.add(new InsnNode(Opcodes.DASTORE));
        incrementLength(insn, owner);
        insn.add(new InsnNode(Opcodes.RETURN));
        insn.add(original);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LIST_FIELD, "Ljava/util/List;"));
        insn.add(new VarInsnNode(Opcodes.DLOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
            "(D)Ljava/lang/Double;", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
            "(Ljava/lang/Object;)Z", true));
        insn.add(new InsnNode(Opcodes.POP));
        incrementLength(insn, owner);
        insn.add(new InsnNode(Opcodes.RETURN));
    }

    private static void rewriteGet(String owner, MethodNode method) {
        reset(method, 2);
        LabelNode original = new LabelNode();
        InsnList insn = method.instructions;
        checkIndex(insn, owner);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, original));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new InsnNode(Opcodes.DALOAD));
        insn.add(new InsnNode(Opcodes.DRETURN));
        insn.add(original);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LIST_FIELD, "Ljava/util/List;"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
            "(I)Ljava/lang/Object;", true));
        insn.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
        insn.add(new InsnNode(Opcodes.DRETURN));
    }

    private static void rewriteSet(String owner, MethodNode method) {
        reset(method, 4);
        LabelNode original = new LabelNode();
        InsnList insn = method.instructions;
        checkIndex(insn, owner);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new JumpInsnNode(Opcodes.IFNULL, original));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.DLOAD, 2));
        insn.add(new InsnNode(Opcodes.DASTORE));
        insn.add(new InsnNode(Opcodes.RETURN));
        insn.add(original);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LIST_FIELD, "Ljava/util/List;"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.DLOAD, 2));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
            "(D)Ljava/lang/Double;", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "set",
            "(ILjava/lang/Object;)Ljava/lang/Object;", true));
        insn.add(new InsnNode(Opcodes.POP));
        insn.add(new InsnNode(Opcodes.RETURN));
    }

    private static void rewriteTimes(String owner, MethodNode method) {
        reset(method, 4);
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        newEmptyTuple(insn, owner);
        insn.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 3));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)D", false));
        insn.add(new VarInsnNode(Opcodes.FLOAD, 1));
        insn.add(new InsnNode(Opcodes.F2D));
        insn.add(new InsnNode(Opcodes.DMUL));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "put", "(D)V", false));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(done);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void rewritePlus(String owner, MethodNode method) {
        reset(method, 4);
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        newEmptyTuple(insn, owner);
        insn.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 3));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)D", false));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)D", false));
        insn.add(new InsnNode(Opcodes.DADD));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "put", "(D)V", false));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(done);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void rewriteValues(String owner, MethodNode method) {
        reset(method, 1);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LIST_FIELD, "Ljava/util/List;"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void rewriteSize(String owner, MethodNode method) {
        reset(method, 1);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
    }

    private static MethodNode createEnsureCapacity(String owner) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "ice$ensureCapacity", "(I)V", null, null);
        method.maxLocals = 2;
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insn.add(new InsnNode(Opcodes.ICONST_1));
        insn.add(new InsnNode(Opcodes.ISHL));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf", "([DI)[D", false));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, VALUES_FIELD, "[D"));
        insn.add(done);
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
        insn.add(new JumpInsnNode(Opcodes.IFLT, invalid));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPLT, valid));
        insn.add(invalid);
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IndexOutOfBoundsException"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        insn.add(new LdcInsnNode("No corresponding noise value in Noise Tuple for index: "));
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
        String tuple = "L" + owner + ";";
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, COPY_METHOD, "()" + tuple, null, null);
        method.maxLocals = 3;
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_DOUBLE));
        insn.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 2));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)D", false));
        insn.add(new InsnNode(Opcodes.DASTORE));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(2, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(done);
        insn.add(new TypeInsnNode(Opcodes.NEW, owner));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", "([D)V", false));
        insn.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static MethodNode createBlend(String owner) {
        String tuple = "L" + owner + ";";
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, BLEND_METHOD,
            "(" + tuple + "F" + tuple + "F)" + tuple, null, null);
        method.maxLocals = 6;
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "size", "()I", false));
        insn.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_DOUBLE));
        insn.add(new VarInsnNode(Opcodes.ASTORE, 4));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 5));
        insn.add(loop);
        insn.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "size", "()I", false));
        insn.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)D", false));
        insn.add(new VarInsnNode(Opcodes.FLOAD, 1));
        insn.add(new InsnNode(Opcodes.F2D));
        insn.add(new InsnNode(Opcodes.DMUL));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "get", "(I)D", false));
        insn.add(new VarInsnNode(Opcodes.FLOAD, 3));
        insn.add(new InsnNode(Opcodes.F2D));
        insn.add(new InsnNode(Opcodes.DMUL));
        insn.add(new InsnNode(Opcodes.DADD));
        insn.add(new InsnNode(Opcodes.DASTORE));
        insn.add(new org.objectweb.asm.tree.IincInsnNode(5, 1));
        insn.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insn.add(done);
        insn.add(new TypeInsnNode(Opcodes.NEW, owner));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", "([D)V", false));
        insn.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static void incrementLength(InsnList insn, String owner) {
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, LENGTH_FIELD, "I"));
        insn.add(new InsnNode(Opcodes.ICONST_1));
        insn.add(new InsnNode(Opcodes.IADD));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, LENGTH_FIELD, "I"));
    }

    private static void checkIndex(InsnList insn, String owner) {
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "ice$checkIndex", "(I)V", false));
    }

    private static void newEmptyTuple(InsnList insn, String owner) {
        insn.add(new TypeInsnNode(Opcodes.NEW, owner));
        insn.add(new InsnNode(Opcodes.DUP));
        insn.add(new InsnNode(Opcodes.ICONST_0));
        insn.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_DOUBLE));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", "([D)V", false));
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
            throw new IllegalStateException("Better Caves NoiseTuple " + name + descriptor
                + " 匹配数量应为 1，实际 " + count);
        }
        return match;
    }

    private static void rejectMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name)) {
                throw new IllegalStateException("Better Caves NoiseTuple 方法冲突：" + name);
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
            throw new IllegalStateException("Better Caves NoiseTuple 调用图变化：" + method.name
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
