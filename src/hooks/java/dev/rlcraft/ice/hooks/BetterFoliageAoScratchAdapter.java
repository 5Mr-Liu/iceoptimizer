package dev.rlcraft.ice.hooks;

import java.util.HashSet;
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
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Reuses only Better Foliage's two per-call AO scratch objects. The six
 * AoFaceData instances belong to OctarineCore's per-thread ModelRenderer.
 */
final class BetterFoliageAoScratchAdapter implements OptimizerBytecodeAdapter {
    static final String UPDATE_METHOD = "update";
    static final String UPDATE_DESCRIPTOR = "(Lmods/octarinecore/common/Int3;ZF)V";
    static final String CONSTRUCTOR_DESCRIPTOR = "(Lnet/minecraft/util/EnumFacing;)V";
    static final String FLOAT_FIELD = "rlcraftIce$quadBounds";
    static final String FLAGS_FIELD = "rlcraftIce$boundsFlags";
    static final String BRIDGE_OWNER =
        "dev/rlcraft/ice/optimizer/compat/chunk/BetterFoliageAoBridge";
    static final String ENABLE_METHOD = "useScratch";
    static final String ENABLE_DESCRIPTOR = "()Z";
    private static final String BIT_SET = "java/util/BitSet";
    private static final String AO_FACE =
        "net/minecraft/client/renderer/BlockModelRenderer$AmbientOcclusionFace";
    private static final String AO_METHOD = "func_187491_a";
    private static final String AO_DESCRIPTOR =
        "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/block/state/IBlockState;"
            + "Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;[FLjava/util/BitSet;)V";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        String expectedName = transformedName.replace('.', '/');
        if (!expectedName.equals(node.name)) {
            throw new IllegalStateException("Better Foliage 类名变化：" + node.name);
        }
        rejectFieldCollision(node, FLOAT_FIELD);
        rejectFieldCollision(node, FLAGS_FIELD);

        MethodNode constructor = null;
        MethodNode update = null;
        int constructorMatches = 0;
        int updateMatches = 0;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && CONSTRUCTOR_DESCRIPTOR.equals(method.desc)) {
                constructor = method;
                constructorMatches++;
            }
            if (UPDATE_METHOD.equals(method.name) && UPDATE_DESCRIPTOR.equals(method.desc)) {
                update = method;
                updateMatches++;
            }
        }
        if (constructorMatches != 1 || updateMatches != 1 || constructor == null || update == null) {
            throw new IllegalStateException("Better Foliage 调用图变化：constructors="
                + constructorMatches + ", updates=" + updateMatches);
        }
        if ((update.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException("Better Foliage AoFaceData.update 不再是实例方法");
        }

        MethodInsnNode superCall = null;
        int superCalls = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && "java/lang/Object".equals(call.owner)
                    && "<init>".equals(call.name) && "()V".equals(call.desc)) {
                    superCall = call;
                    superCalls++;
                }
            }
        }
        if (superCalls != 1 || superCall == null) {
            throw new IllegalStateException("Better Foliage 构造链变化：Object.<init>=" + superCalls);
        }

        IntInsnNode floatLength = null;
        IntInsnNode floatAllocation = null;
        VarInsnNode floatStore = null;
        TypeInsnNode bitSetNew = null;
        InsnNode bitSetDup = null;
        InsnNode bitSetSize = null;
        MethodInsnNode bitSetConstructor = null;
        VarInsnNode bitSetStore = null;
        MethodInsnNode aoCall = null;
        VarInsnNode aoFloatLoad = null;
        VarInsnNode aoFlagsLoad = null;
        int floatAllocations = 0;
        int bitSetAllocations = 0;
        int aoCalls = 0;
        for (AbstractInsnNode instruction : update.instructions.toArray()) {
            if (instruction instanceof IntInsnNode && instruction.getOpcode() == Opcodes.NEWARRAY
                && ((IntInsnNode) instruction).operand == Opcodes.T_FLOAT) {
                floatAllocations++;
                AbstractInsnNode previous = previousOpcode(instruction);
                if (previous instanceof IntInsnNode && previous.getOpcode() == Opcodes.BIPUSH
                    && ((IntInsnNode) previous).operand == 12) {
                    floatLength = (IntInsnNode) previous;
                    floatAllocation = (IntInsnNode) instruction;
                    AbstractInsnNode store = nextOpcode(instruction);
                    if (store instanceof VarInsnNode && store.getOpcode() == Opcodes.ASTORE) {
                        floatStore = (VarInsnNode) store;
                    }
                }
            }
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                && BIT_SET.equals(((TypeInsnNode) instruction).desc)) {
                bitSetAllocations++;
                AbstractInsnNode duplicate = nextOpcode(instruction);
                AbstractInsnNode size = nextOpcode(duplicate);
                AbstractInsnNode constructorCall = nextOpcode(size);
                if (duplicate instanceof InsnNode && duplicate.getOpcode() == Opcodes.DUP
                    && size instanceof InsnNode && size.getOpcode() == Opcodes.ICONST_3
                    && constructorCall instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) constructorCall;
                    if (call.getOpcode() == Opcodes.INVOKESPECIAL && BIT_SET.equals(call.owner)
                        && "<init>".equals(call.name) && "(I)V".equals(call.desc)) {
                        bitSetNew = (TypeInsnNode) instruction;
                        bitSetDup = (InsnNode) duplicate;
                        bitSetSize = (InsnNode) size;
                        bitSetConstructor = call;
                        AbstractInsnNode store = nextOpcode(call);
                        if (store instanceof VarInsnNode && store.getOpcode() == Opcodes.ASTORE) {
                            bitSetStore = (VarInsnNode) store;
                        }
                    }
                }
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && AO_FACE.equals(call.owner)
                    && AO_METHOD.equals(call.name) && AO_DESCRIPTOR.equals(call.desc)) {
                    aoCalls++;
                    aoCall = call;
                    AbstractInsnNode flags = previousOpcode(call);
                    AbstractInsnNode floats = previousOpcode(flags);
                    if (flags instanceof VarInsnNode && flags.getOpcode() == Opcodes.ALOAD) {
                        aoFlagsLoad = (VarInsnNode) flags;
                    }
                    if (floats instanceof VarInsnNode && floats.getOpcode() == Opcodes.ALOAD) {
                        aoFloatLoad = (VarInsnNode) floats;
                    }
                }
            }
        }
        if (floatAllocations != 1 || bitSetAllocations != 1 || floatLength == null
            || floatAllocation == null || bitSetNew == null || bitSetDup == null
            || bitSetSize == null || bitSetConstructor == null || floatStore == null
            || bitSetStore == null) {
            throw new IllegalStateException("Better Foliage AO 暂存调用图变化：float[12]="
                + floatAllocations + ", BitSet(3)=" + bitSetAllocations);
        }
        if (aoCalls != 1 || aoCall == null || aoFloatLoad == null || aoFlagsLoad == null
            || aoFloatLoad.var != floatStore.var
            || !isLocalAlias(update, bitSetStore, aoCall, bitSetStore.var, aoFlagsLoad.var)) {
            throw new IllegalStateException("Better Foliage AO 参数流变化：aoCalls=" + aoCalls
                + ", floatLocal=" + (floatStore == null ? -1 : floatStore.var)
                + ", flagsLocal=" + (bitSetStore == null ? -1 : bitSetStore.var));
        }

        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FLOAT_FIELD, "[F", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FLAGS_FIELD, "Ljava/util/BitSet;", null, null));

        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new IntInsnNode(Opcodes.BIPUSH, 12));
        initialize.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_FLOAT));
        initialize.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FLOAT_FIELD, "[F"));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new TypeInsnNode(Opcodes.NEW, BIT_SET));
        initialize.add(new InsnNode(Opcodes.DUP));
        initialize.add(new InsnNode(Opcodes.ICONST_3));
        initialize.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BIT_SET, "<init>", "(I)V", false));
        initialize.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FLAGS_FIELD, "Ljava/util/BitSet;"));
        constructor.instructions.insert(superCall, initialize);

        int enabledLocal = update.maxLocals++;
        InsnList selectFloats = new InsnList();
        selectFloats.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
            ENABLE_METHOD, ENABLE_DESCRIPTOR, false));
        selectFloats.add(new VarInsnNode(Opcodes.ISTORE, enabledLocal));
        LabelNode originalFloats = new LabelNode();
        LabelNode floatsReady = new LabelNode();
        selectFloats.add(new VarInsnNode(Opcodes.ILOAD, enabledLocal));
        selectFloats.add(new JumpInsnNode(Opcodes.IFEQ, originalFloats));
        selectFloats.add(new VarInsnNode(Opcodes.ALOAD, 0));
        selectFloats.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FLOAT_FIELD, "[F"));
        selectFloats.add(new JumpInsnNode(Opcodes.GOTO, floatsReady));
        selectFloats.add(originalFloats);
        selectFloats.add(new IntInsnNode(Opcodes.BIPUSH, 12));
        selectFloats.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_FLOAT));
        selectFloats.add(floatsReady);
        update.instructions.insertBefore(floatLength, selectFloats);
        update.instructions.remove(floatLength);
        update.instructions.remove(floatAllocation);

        InsnList selectFlags = new InsnList();
        LabelNode originalFlags = new LabelNode();
        LabelNode flagsReady = new LabelNode();
        selectFlags.add(new VarInsnNode(Opcodes.ILOAD, enabledLocal));
        selectFlags.add(new JumpInsnNode(Opcodes.IFEQ, originalFlags));
        selectFlags.add(new VarInsnNode(Opcodes.ALOAD, 0));
        selectFlags.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FLAGS_FIELD, "Ljava/util/BitSet;"));
        selectFlags.add(new InsnNode(Opcodes.DUP));
        selectFlags.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BIT_SET, "clear", "()V", false));
        selectFlags.add(new JumpInsnNode(Opcodes.GOTO, flagsReady));
        selectFlags.add(originalFlags);
        selectFlags.add(new TypeInsnNode(Opcodes.NEW, BIT_SET));
        selectFlags.add(new InsnNode(Opcodes.DUP));
        selectFlags.add(new InsnNode(Opcodes.ICONST_3));
        selectFlags.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BIT_SET, "<init>", "(I)V", false));
        selectFlags.add(flagsReady);
        update.instructions.insertBefore(bitSetNew, selectFlags);
        update.instructions.remove(bitSetNew);
        update.instructions.remove(bitSetDup);
        update.instructions.remove(bitSetSize);
        update.instructions.remove(bitSetConstructor);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void rejectFieldCollision(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) {
                throw new IllegalStateException("Better Foliage 已存在 ICE 字段：" + name);
            }
        }
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

    private static boolean isLocalAlias(MethodNode method, AbstractInsnNode start, AbstractInsnNode end,
                                        int originalLocal, int consumedLocal) {
        Set<Integer> aliases = new HashSet<Integer>();
        aliases.add(Integer.valueOf(originalLocal));
        for (AbstractInsnNode current = start.getNext(); current != null && current != end;
             current = current.getNext()) {
            if (!(current instanceof VarInsnNode) || current.getOpcode() != Opcodes.ASTORE) continue;
            VarInsnNode store = (VarInsnNode) current;
            AbstractInsnNode source = previousOpcode(current);
            boolean alias = source instanceof VarInsnNode && source.getOpcode() == Opcodes.ALOAD
                && aliases.contains(Integer.valueOf(((VarInsnNode) source).var));
            aliases.remove(Integer.valueOf(store.var));
            if (alias) aliases.add(Integer.valueOf(store.var));
        }
        return aliases.contains(Integer.valueOf(consumedLocal));
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
