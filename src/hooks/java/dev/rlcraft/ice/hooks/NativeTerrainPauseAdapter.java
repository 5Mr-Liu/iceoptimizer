package dev.rlcraft.ice.hooks;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Retains the incoming terrain implementation for the explicit F6 comparison. */
final class NativeTerrainPauseAdapter {
    static final String PREFIX = "ice$pausedTerrain$";
    private static final String REGISTRY = "dev/rlcraft/ice/optimizer/OptimizerRegistry";

    static MethodNode copy(MethodNode method) {
        MethodNode copy = new MethodNode(Opcodes.ASM5, method.access, method.name, method.desc,
            method.signature, method.exceptions.toArray(new String[method.exceptions.size()]));
        method.accept(copy);
        return copy;
    }

    static void install(ClassNode owner, MethodNode optimized, MethodNode incoming) {
        if (incoming.name.startsWith("<") || (incoming.access & Opcodes.ACC_STATIC) != 0)
            throw new IllegalArgumentException("Expected instance terrain method");
        incoming.name = PREFIX + incoming.name;
        for (MethodNode method : owner.methods)
            if (method.name.equals(incoming.name) && method.desc.equals(incoming.desc))
                throw new IllegalStateException("Duplicate terrain pause path");
        incoming.access = (incoming.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        owner.methods.add(incoming);
        InsnList dispatch = new InsnList();
        LabelNode enabled = new LabelNode();
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REGISTRY,
            "isRuntimePaused", "()Z", false));
        dispatch.add(new JumpInsnNode(Opcodes.IFEQ, enabled));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type argument : Type.getArgumentTypes(optimized.desc)) {
            dispatch.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name,
            incoming.name, incoming.desc, false));
        dispatch.add(new InsnNode(Type.getReturnType(optimized.desc).getOpcode(Opcodes.IRETURN)));
        dispatch.add(enabled);
        optimized.instructions.insert(dispatch);
    }
}
