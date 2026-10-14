package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Changes only BO3's temporary block representation; all world checks remain in place. */
final class OtgBo3ExecutionAdapter implements OptimizerBytecodeAdapter {
    static final String BO3 = "com/pg85/otg/customobjects/bo3/BO3";
    static final String CONFIG = "com/pg85/otg/customobjects/bo3/BO3Config";
    static final String BLOCK = "com/pg85/otg/customobjects/bo3/bo3function/BO3BlockFunction";
    static final String EXTRUSION = "com/pg85/otg/customobjects/bo3/ObjectExtrusionHelper";
    static final String WORK = "dev/rlcraft/ice/optimizer/compat/otg/OtgBo3Workspace";
    static final String ARRAY = "[L" + BLOCK + ";";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode method = null;
        for (MethodNode candidate : node.methods) if ("trySpawnAt".equals(candidate.name)) {
            if (method != null) throw new IllegalStateException("Ambiguous BO3 trySpawnAt");
            method = candidate;
        }
        if (method == null) throw new IllegalStateException("Missing BO3 trySpawnAt");
        int scope = method.maxLocals++;
        int exception = method.maxLocals++;
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList prefix = new InsnList();
        prefix.add(new InsnNode(Opcodes.ACONST_NULL)); prefix.add(new VarInsnNode(Opcodes.ASTORE, scope)); prefix.add(start);
        method.instructions.insert(prefix);
        boolean afterBlocks = false;
        int accesses = 0, lengths = 0, begins = 0, spawns = 0, lists = 0, extruders = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (CONFIG.equals(call.owner) && "getBlocks".equals(call.name) && ("(I)" + ARRAY).equals(call.desc)) {
                    InsnList change = new InsnList();
                    change.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "begin", "(L" + CONFIG + ";I)L" + WORK + ";", false));
                    change.add(new InsnNode(Opcodes.DUP)); change.add(new VarInsnNode(Opcodes.ASTORE, scope));
                    change.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORK, "blocks", "()" + ARRAY, false));
                    method.instructions.insertBefore(call, change); method.instructions.remove(call);
                    afterBlocks = true; begins++; continue;
                }
                if (afterBlocks && BLOCK.equals(call.owner) && "spawn".equals(call.name)) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, scope));
                    call.setOpcode(Opcodes.INVOKESTATIC); call.owner = WORK;
                    call.desc = "(L" + BLOCK + ";" + call.desc.substring(1, call.desc.length() - 2) + "L" + WORK + ";)V";
                    spawns++; continue;
                }
                if (afterBlocks && EXTRUSION.equals(call.owner)) {
                    call.owner = WORK + "$Extrusion";
                    continue;
                }
            }
            if (afterBlocks && instruction instanceof TypeInsnNode) {
                TypeInsnNode type = (TypeInsnNode) instruction;
                if (type.getOpcode() == Opcodes.NEW && "java/util/ArrayList".equals(type.desc)) {
                    replaceAllocation(method, type, scope, "selected", "()Ljava/util/ArrayList;"); lists++;
                } else if (type.getOpcode() == Opcodes.NEW && EXTRUSION.equals(type.desc)) {
                    // Receiver goes before the original mode/material arguments.
                    AbstractInsnNode duplicate = next(type);
                    method.instructions.set(type, new VarInsnNode(Opcodes.ALOAD, scope));
                    method.instructions.remove(duplicate);
                    extruders++;
                } else if (type.getOpcode() == Opcodes.INSTANCEOF && BLOCK.equals(type.desc)) {
                    method.instructions.insertBefore(type, new VarInsnNode(Opcodes.ALOAD, scope));
                    method.instructions.set(type, new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "isBlock", "(L" + BLOCK + ";L" + WORK + ";)Z", false));
                }
            }
            if (afterBlocks && instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.GETFIELD && BLOCK.equals(field.owner)
                    && ("x".equals(field.name) || "y".equals(field.name) || "z".equals(field.name))) {
                    method.instructions.insertBefore(field, new VarInsnNode(Opcodes.ALOAD, scope));
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, field.name,
                        "(L" + BLOCK + ";L" + WORK + ";)" + field.desc, false));
                }
            }
            if (afterBlocks && instruction.getOpcode() == Opcodes.AALOAD) {
                method.instructions.insertBefore(instruction, new VarInsnNode(Opcodes.ALOAD, scope));
                method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "blockAt", "(" + ARRAY + "IL" + WORK + ";)L" + BLOCK + ";", false));
                accesses++;
            } else if (afterBlocks && instruction.getOpcode() == Opcodes.ARRAYLENGTH) {
                method.instructions.insertBefore(instruction, new VarInsnNode(Opcodes.ALOAD, scope));
                method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "length", "(" + ARRAY + "L" + WORK + ";)I", false)); lengths++;
            }
            if (instruction.getOpcode() == Opcodes.IRETURN) releaseBefore(method, instruction, scope);
        }
        // The extrusion constructor has the same two arguments, but now uses the pooled receiver.
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ((WORK + "$Extrusion").equals(call.owner) && "<init>".equals(call.name)) {
                call.setOpcode(Opcodes.INVOKEVIRTUAL); call.owner = WORK; call.name = "extrusion";
                call.desc = call.desc.substring(0, call.desc.length() - 1) + "L" + WORK + "$Extrusion;";
            }
        }
        if (begins != 1 || accesses != 2 || lengths != 3 || spawns != 1 || lists != 1 || extruders != 1)
            throw new IllegalStateException("BO3 execution shape changed: " + begins + "/" + accesses + "/" + lengths + "/" + spawns + "/" + lists + "/" + extruders);
        method.instructions.add(end); method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, exception));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, scope));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "release", "(L" + WORK + ";)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, exception)); method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        if (method.localVariables != null) method.localVariables.clear();
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        return OtgAsmMethodWriter.write(reader, writer, method);
    }
    private static void replaceAllocation(MethodNode method, TypeInsnNode allocation, int scope, String name, String desc) {
        AbstractInsnNode dup = next(allocation), constructor = next(dup);
        if (dup.getOpcode() != Opcodes.DUP || !(constructor instanceof MethodInsnNode)
            || !"<init>".equals(((MethodInsnNode) constructor).name)) throw new IllegalStateException("BO3 list allocation changed");
        method.instructions.set(allocation, new VarInsnNode(Opcodes.ALOAD, scope));
        method.instructions.remove(dup);
        method.instructions.set(constructor, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORK, name, desc, false));
    }
    private static AbstractInsnNode next(AbstractInsnNode node) {
        AbstractInsnNode next = node == null ? null : node.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }
    private static void releaseBefore(MethodNode method, AbstractInsnNode instruction, int scope) {
        InsnList release = new InsnList(); release.add(new VarInsnNode(Opcodes.ALOAD, scope));
        release.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "release", "(L" + WORK + ";)V", false));
        method.instructions.insertBefore(instruction, release);
    }
}
