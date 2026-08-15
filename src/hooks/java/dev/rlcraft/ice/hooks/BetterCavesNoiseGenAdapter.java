package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
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
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Caches duplicate corner columns and fuses Better Caves' tuple interpolation. */
final class BetterCavesNoiseGenAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/bettercaves/BetterCavesOptimizationBridge";
    static final String RAW_METHOD = "ice$raw$generateNoiseColumn";
    private static final String GENERATE_METHOD = "generateNoiseColumn";
    private static final String BLOCK_POS = "net/minecraft/util/math/BlockPos";
    private static final String COLUMN =
        "com/yungnickyoung/minecraft/bettercaves/noise/NoiseColumn";
    private static final String COLUMN_DESC = "L" + COLUMN + ";";
    private static final String TUPLE =
        "com/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple";
    private static final String TUPLE_DESC = "L" + TUPLE + ";";
    private static final String GENERATE_DESC = "(L" + BLOCK_POS + ";II)" + COLUMN_DESC;
    private static final String POSITION_KEYS = "ice$cachePositionKeys";
    private static final String RANGE_KEYS = "ice$cacheRangeKeys";
    private static final String USED = "ice$cacheUsed";
    private static final String COLUMNS = "ice$cacheColumns";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("Better Caves NoiseGen 类名变化：" + node.name);
        }
        rejectCollisions(node);
        MethodNode generate = find(node, GENERATE_METHOD, GENERATE_DESC);
        if ((generate.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException("Better Caves generateNoiseColumn 不再是实例方法");
        }
        String[] coordinateGetters = validateGenerateGraph(generate);
        int fused = fuseInterpolations(node);
        if (fused != 4) {
            throw new IllegalStateException("Better Caves 插值调用图变化：blend=" + fused);
        }

        int wrapperAccess = generate.access;
        String wrapperSignature = generate.signature;
        @SuppressWarnings("unchecked")
        List<String> exceptionList = generate.exceptions;
        String[] exceptions = exceptionList == null
            ? null : exceptionList.toArray(new String[exceptionList.size()]);
        generate.name = RAW_METHOD;
        generate.access = (generate.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            POSITION_KEYS, "[J", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            RANGE_KEYS, "[J", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            USED, "[Z", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            COLUMNS, "[" + COLUMN_DESC, null, null));
        node.methods.add(createWrapper(node.name, wrapperAccess, wrapperSignature, exceptions,
            coordinateGetters[0], coordinateGetters[1]));

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void rejectCollisions(ClassNode node) {
        for (FieldNode field : node.fields) {
            if (POSITION_KEYS.equals(field.name) || RANGE_KEYS.equals(field.name)
                || USED.equals(field.name) || COLUMNS.equals(field.name)) {
                throw new IllegalStateException("Better Caves NoiseGen 字段冲突：" + field.name);
            }
        }
        for (MethodNode method : node.methods) {
            if (RAW_METHOD.equals(method.name)) {
                throw new IllegalStateException("Better Caves NoiseGen 已存在 ICE raw 方法");
            }
        }
    }

    private static String[] validateGenerateGraph(MethodNode method) {
        int newColumns = 0;
        int newTuples = 0;
        int noiseCalls = 0;
        int puts = 0;
        List<String> coordinateGetters = new ArrayList<String>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW) {
                String type = ((TypeInsnNode) instruction).desc;
                if (COLUMN.equals(type)) newColumns++;
                if (TUPLE.equals(type)) newTuples++;
            }
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (BLOCK_POS.equals(call.owner) && "()I".equals(call.desc)) {
                coordinateGetters.add(call.name);
            }
            if ("com/yungnickyoung/minecraft/bettercaves/noise/INoiseLibrary".equals(call.owner)
                && "GetNoise".equals(call.name) && "(FFF)F".equals(call.desc)) noiseCalls++;
            if (COLUMN.equals(call.owner) && "put".equals(call.name)
                && ("(I" + TUPLE_DESC + ")V").equals(call.desc)) puts++;
        }
        if (newColumns != 1 || newTuples != 1 || noiseCalls != 1 || puts != 1
            || coordinateGetters.size() != 2) {
            throw new IllegalStateException("Better Caves generateNoiseColumn 调用图变化：columns="
                + newColumns + ", tuples=" + newTuples + ", noise=" + noiseCalls
                + ", puts=" + puts + ", coordinates=" + coordinateGetters.size());
        }
        return new String[] { coordinateGetters.get(0), coordinateGetters.get(1) };
    }

    private static int fuseInterpolations(ClassNode node) {
        String timesDesc = "(F)" + TUPLE_DESC;
        String plusDesc = "(" + TUPLE_DESC + ")" + TUPLE_DESC;
        String blendDesc = "(" + TUPLE_DESC + "F" + TUPLE_DESC + "F)" + TUPLE_DESC;
        int times = 0;
        int pluses = 0;
        List<Fusion> fusions = new ArrayList<Fusion>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (TUPLE.equals(call.owner) && "times".equals(call.name) && timesDesc.equals(call.desc)) {
                    times++;
                }
                if (!TUPLE.equals(call.owner) || !"plus".equals(call.name)
                    || !plusDesc.equals(call.desc)) continue;
                pluses++;
                MethodInsnNode secondTimes = previousMethod(call);
                MethodInsnNode firstTimes = previousMethod(secondTimes);
                if (secondTimes == null || firstTimes == null
                    || !TUPLE.equals(secondTimes.owner) || !"times".equals(secondTimes.name)
                    || !timesDesc.equals(secondTimes.desc)
                    || !TUPLE.equals(firstTimes.owner) || !"times".equals(firstTimes.name)
                    || !timesDesc.equals(firstTimes.desc)) {
                    throw new IllegalStateException("Better Caves times+plus 插值栈形状变化：" + method.name);
                }
                AbstractInsnNode firstCoefficient = previousOpcode(firstTimes);
                AbstractInsnNode firstTuple = previousOpcode(firstCoefficient);
                AbstractInsnNode secondCoefficient = previousOpcode(secondTimes);
                AbstractInsnNode secondTuple = previousOpcode(secondCoefficient);
                if (!(firstTuple instanceof VarInsnNode) || firstTuple.getOpcode() != Opcodes.ALOAD
                    || !(firstCoefficient instanceof VarInsnNode)
                    || firstCoefficient.getOpcode() != Opcodes.FLOAD
                    || !(secondTuple instanceof VarInsnNode) || secondTuple.getOpcode() != Opcodes.ALOAD
                    || !(secondCoefficient instanceof VarInsnNode)
                    || secondCoefficient.getOpcode() != Opcodes.FLOAD
                    || nextOpcode(firstTuple) != firstCoefficient
                    || nextOpcode(firstCoefficient) != firstTimes
                    || nextOpcode(firstTimes) != secondTuple
                    || nextOpcode(secondTuple) != secondCoefficient
                    || nextOpcode(secondCoefficient) != secondTimes
                    || nextOpcode(secondTimes) != call) {
                    throw new IllegalStateException("Better Caves 插值参数装载结构变化：" + method.name);
                }
                fusions.add(new Fusion(method, (VarInsnNode) firstTuple,
                    (VarInsnNode) firstCoefficient, (VarInsnNode) secondTuple,
                    (VarInsnNode) secondCoefficient, call));
            }
        }
        if (times != 8 || pluses != 4 || fusions.size() != 4) {
            throw new IllegalStateException("Better Caves 插值数量变化：times=" + times
                + ", plus=" + pluses);
        }
        for (Fusion fusion : fusions) {
            LabelNode original = new LabelNode();
            LabelNode done = new LabelNode();
            InsnList replacement = new InsnList();
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                "isPipelineEnabled", "()Z", false));
            replacement.add(new JumpInsnNode(Opcodes.IFEQ, original));
            loadBlendArguments(replacement, fusion);
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TUPLE,
                BetterCavesNoiseTupleAdapter.BLEND_METHOD, blendDesc, false));
            replacement.add(new JumpInsnNode(Opcodes.GOTO, done));
            replacement.add(original);
            replacement.add(new VarInsnNode(Opcodes.ALOAD, fusion.firstTuple.var));
            replacement.add(new VarInsnNode(Opcodes.FLOAD, fusion.firstCoefficient.var));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TUPLE,
                "times", timesDesc, false));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, fusion.secondTuple.var));
            replacement.add(new VarInsnNode(Opcodes.FLOAD, fusion.secondCoefficient.var));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TUPLE,
                "times", timesDesc, false));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TUPLE,
                "plus", plusDesc, false));
            replacement.add(done);
            fusion.method.instructions.insertBefore(fusion.firstTuple, replacement);
            AbstractInsnNode current = fusion.firstTuple;
            while (current != null) {
                AbstractInsnNode next = current.getNext();
                fusion.method.instructions.remove(current);
                if (current == fusion.plus) break;
                current = next;
            }
        }
        return fusions.size();
    }

    private static void loadBlendArguments(InsnList insn, Fusion fusion) {
        insn.add(new VarInsnNode(Opcodes.ALOAD, fusion.firstTuple.var));
        insn.add(new VarInsnNode(Opcodes.FLOAD, fusion.firstCoefficient.var));
        insn.add(new VarInsnNode(Opcodes.ALOAD, fusion.secondTuple.var));
        insn.add(new VarInsnNode(Opcodes.FLOAD, fusion.secondCoefficient.var));
    }

    private static MethodNode createWrapper(String owner, int access, String signature,
                                            String[] exceptions, String getX, String getZ) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, GENERATE_METHOD,
            GENERATE_DESC, signature, exceptions);
        method.maxLocals = 12;
        LabelNode enabled = new LabelNode();
        LabelNode initialized = new LabelNode();
        LabelNode miss = new LabelNode();
        InsnList insn = method.instructions;
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "isPipelineEnabled", "()Z", false));
        insn.add(new JumpInsnNode(Opcodes.IFNE, enabled));
        callRaw(insn, owner);
        insn.add(new InsnNode(Opcodes.ARETURN));

        insn.add(enabled);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, COLUMNS, "[" + COLUMN_DESC));
        insn.add(new JumpInsnNode(Opcodes.IFNONNULL, initialized));
        initializeLongArray(insn, owner, POSITION_KEYS);
        initializeLongArray(insn, owner, RANGE_KEYS);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new IntInsnNode(Opcodes.BIPUSH, 64));
        insn.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, USED, "[Z"));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new IntInsnNode(Opcodes.BIPUSH, 64));
        insn.add(new TypeInsnNode(Opcodes.ANEWARRAY, COLUMN));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, COLUMNS, "[" + COLUMN_DESC));
        insn.add(initialized);

        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_POS, getX, "()I", false));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 4));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_POS, getZ, "()I", false));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 5));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "pair", "(II)J", false));
        insn.add(new VarInsnNode(Opcodes.LSTORE, 6));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "pair", "(II)J", false));
        insn.add(new VarInsnNode(Opcodes.LSTORE, 8));
        insn.add(new VarInsnNode(Opcodes.LLOAD, 6));
        insn.add(new VarInsnNode(Opcodes.LLOAD, 8));
        insn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "cacheIndex", "(JJ)I", false));
        insn.add(new VarInsnNode(Opcodes.ISTORE, 10));

        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, USED, "[Z"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new InsnNode(Opcodes.BALOAD));
        insn.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, POSITION_KEYS, "[J"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new InsnNode(Opcodes.LALOAD));
        insn.add(new VarInsnNode(Opcodes.LLOAD, 6));
        insn.add(new InsnNode(Opcodes.LCMP));
        insn.add(new JumpInsnNode(Opcodes.IFNE, miss));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, RANGE_KEYS, "[J"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new InsnNode(Opcodes.LALOAD));
        insn.add(new VarInsnNode(Opcodes.LLOAD, 8));
        insn.add(new InsnNode(Opcodes.LCMP));
        insn.add(new JumpInsnNode(Opcodes.IFNE, miss));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, COLUMNS, "[" + COLUMN_DESC));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new InsnNode(Opcodes.AALOAD));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COLUMN,
            BetterCavesNoiseColumnAdapter.COPY_METHOD, "()" + COLUMN_DESC, false));
        insn.add(new InsnNode(Opcodes.ARETURN));

        insn.add(miss);
        callRaw(insn, owner);
        insn.add(new VarInsnNode(Opcodes.ASTORE, 11));
        storeLong(insn, owner, POSITION_KEYS, 6);
        storeLong(insn, owner, RANGE_KEYS, 8);
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, USED, "[Z"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new InsnNode(Opcodes.ICONST_1));
        insn.add(new InsnNode(Opcodes.BASTORE));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, COLUMNS, "[" + COLUMN_DESC));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insn.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COLUMN,
            BetterCavesNoiseColumnAdapter.COPY_METHOD, "()" + COLUMN_DESC, false));
        insn.add(new InsnNode(Opcodes.AASTORE));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 11));
        insn.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static void callRaw(InsnList insn, String owner) {
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, RAW_METHOD, GENERATE_DESC, false));
    }

    private static void initializeLongArray(InsnList insn, String owner, String field) {
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new IntInsnNode(Opcodes.BIPUSH, 64));
        insn.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        insn.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "[J"));
    }

    private static void storeLong(InsnList insn, String owner, String field, int local) {
        insn.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insn.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "[J"));
        insn.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insn.add(new VarInsnNode(Opcodes.LLOAD, local));
        insn.add(new InsnNode(Opcodes.LASTORE));
    }

    private static MethodInsnNode previousMethod(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null) {
            if (current instanceof MethodInsnNode) return (MethodInsnNode) current;
            current = current.getPrevious();
        }
        return null;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
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
            throw new IllegalStateException("Better Caves NoiseGen " + name + descriptor
                + " 匹配数量应为 1，实际 " + count);
        }
        return match;
    }

    private static final class Fusion {
        private final MethodNode method;
        private final VarInsnNode firstTuple;
        private final VarInsnNode firstCoefficient;
        private final VarInsnNode secondTuple;
        private final VarInsnNode secondCoefficient;
        private final MethodInsnNode plus;

        private Fusion(MethodNode method, VarInsnNode firstTuple,
                       VarInsnNode firstCoefficient, VarInsnNode secondTuple,
                       VarInsnNode secondCoefficient, MethodInsnNode plus) {
            this.method = method;
            this.firstTuple = firstTuple;
            this.firstCoefficient = firstCoefficient;
            this.secondTuple = secondTuple;
            this.secondCoefficient = secondCoefficient;
            this.plus = plus;
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
