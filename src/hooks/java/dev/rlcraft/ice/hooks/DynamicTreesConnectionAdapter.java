package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Memoizes Dynamic Trees' six immutable connection radii across face queries. */
final class DynamicTreesConnectionAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET_METHOD = "pollConnections";
    static final String TARGET_DESCRIPTOR =
        "(ILnet/minecraftforge/common/property/IExtendedBlockState;)[I";
    static final String RADIUS_METHOD = "getConnectionRadius";
    static final String RADIUS_DESCRIPTOR =
        "(Lnet/minecraftforge/common/property/IExtendedBlockState;"
            + "Lnet/minecraftforge/common/property/IUnlistedProperty;)I";
    static final String BRIDGE_OWNER =
        "dev/rlcraft/ice/optimizer/compat/chunk/DynamicTreesConnectionBridge";
    static final String LOOKUP_DESCRIPTOR = "(Ljava/lang/Object;ILjava/lang/Object;)[I";
    static final String REMEMBER_DESCRIPTOR = "(Ljava/lang/Object;ILjava/lang/Object;[I)[I";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        String expectedName = transformedName.replace('.', '/');
        if (!expectedName.equals(node.name)) {
            throw new IllegalStateException("Dynamic Trees 类名变化：" + node.name);
        }

        MethodNode targetMethod = null;
        int methodMatches = 0;
        for (MethodNode method : node.methods) {
            if (TARGET_METHOD.equals(method.name) && TARGET_DESCRIPTOR.equals(method.desc)) {
                targetMethod = method;
                methodMatches++;
            }
        }
        if (methodMatches != 1 || targetMethod == null || (targetMethod.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException("Dynamic Trees pollConnections 匹配数量应为 1，实际 "
                + methodMatches);
        }

        int intArrays = 0;
        int radiusCalls = 0;
        int returns = 0;
        AbstractInsnNode returnInstruction = null;
        for (AbstractInsnNode instruction : targetMethod.instructions.toArray()) {
            if (instruction instanceof IntInsnNode && instruction.getOpcode() == Opcodes.NEWARRAY
                && ((IntInsnNode) instruction).operand == Opcodes.T_INT) {
                AbstractInsnNode previous = previousOpcode(instruction);
                if (previous instanceof IntInsnNode && previous.getOpcode() == Opcodes.BIPUSH
                    && ((IntInsnNode) previous).operand == 6) intArrays++;
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && node.name.equals(call.owner)
                    && RADIUS_METHOD.equals(call.name) && RADIUS_DESCRIPTOR.equals(call.desc)) {
                    radiusCalls++;
                }
            }
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returns++;
                returnInstruction = instruction;
            }
        }
        if (intArrays != 1 || radiusCalls != 1 || returns != 1 || returnInstruction == null) {
            throw new IllegalStateException("Dynamic Trees 连接调用图变化：int[6]=" + intArrays
                + ", getConnectionRadius=" + radiusCalls + ", areturn=" + returns);
        }

        LabelNode cacheMiss = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lookup.add(new VarInsnNode(Opcodes.ILOAD, 1));
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 2));
        lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
            "lookup", LOOKUP_DESCRIPTOR, false));
        lookup.add(new InsnNode(Opcodes.DUP));
        lookup.add(new JumpInsnNode(Opcodes.IFNULL, cacheMiss));
        lookup.add(new InsnNode(Opcodes.ARETURN));
        lookup.add(cacheMiss);
        lookup.add(new InsnNode(Opcodes.POP));
        targetMethod.instructions.insert(lookup);

        int resultLocal = targetMethod.maxLocals++;
        InsnList remember = new InsnList();
        remember.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
        remember.add(new VarInsnNode(Opcodes.ILOAD, 1));
        remember.add(new VarInsnNode(Opcodes.ALOAD, 2));
        remember.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        remember.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
            "remember", REMEMBER_DESCRIPTOR, false));
        targetMethod.instructions.insertBefore(returnInstruction, remember);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
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
