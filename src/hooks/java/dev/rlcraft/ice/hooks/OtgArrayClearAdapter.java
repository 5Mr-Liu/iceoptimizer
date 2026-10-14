package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Tracks OTG layer request prefixes without changing the manager's synchronization or pool. */
final class OtgArrayClearAdapter implements OptimizerBytecodeAdapter {
    static final String STATE = "dev/rlcraft/ice/optimizer/compat/otg/OtgArrayClearState";
    static final String FIELD = "ice$arrayClearState";
    private static final String[] NEXT = { "smallArraysNext", "mediumArraysNext", "largeArraysNext", "extraLargeArraysNext" };

    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int constructors = 0, returns = 0, clears = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && method.desc.equals("()V")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) continue;
                    InsnList init = new InsnList();
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new TypeInsnNode(Opcodes.NEW, STATE));
                    init.add(new InsnNode(Opcodes.DUP));
                    init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, STATE, "<init>", "()V", false));
                    init.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FIELD, "L" + STATE + ";"));
                    method.instructions.insertBefore(instruction, init);
                    constructors++;
                }
            } else if (method.name.equals("getArray") && method.desc.equals("(I)[I")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                    if (returns >= NEXT.length) throw new IllegalStateException("Changed OTG array return count");
                    InsnList record = state(node.name);
                    record.add(new InsnNode(Opcodes.ICONST_0 + returns));
                    record.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    record.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, NEXT[returns], "I"));
                    record.add(new InsnNode(Opcodes.ICONST_1));
                    record.add(new InsnNode(Opcodes.ISUB));
                    record.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATE, "record",
                        "([IL" + STATE + ";III)[I", false));
                    method.instructions.insertBefore(instruction, record);
                    returns++;
                }
            } else if (method.name.equals("release") && method.desc.equals("()V")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!call.owner.equals("java/util/Arrays") || !call.name.equals("fill") || !call.desc.equals("([II)V")) continue;
                    InsnList arguments = state(node.name);
                    arguments.add(new InsnNode(Opcodes.ICONST_0 + clears));
                    arguments.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    method.instructions.insertBefore(call, arguments);
                    call.owner = STATE;
                    call.name = "clear";
                    call.desc = "([IIL" + STATE + ";II)V";
                    clears++;
                }
            }
        }
        if (constructors != 1 || returns != 4 || clears != 4)
            throw new IllegalStateException("Changed OTG ArraysCache shape " + constructors + "/" + returns + "/" + clears);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FIELD, "L" + STATE + ";", null, null));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static InsnList state(String owner) {
        InsnList result = new InsnList();
        result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new FieldInsnNode(Opcodes.GETFIELD, owner, FIELD, "L" + STATE + ";"));
        return result;
    }
}
