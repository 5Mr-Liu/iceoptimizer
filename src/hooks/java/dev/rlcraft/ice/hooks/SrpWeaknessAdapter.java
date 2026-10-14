package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Replaces only the three weakness loops, preserving the surrounding damage handler. */
final class SrpWeaknessAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityParasiteBase";
    static final String CONFIG = "com/dhanantry/scapeandrunparasites/util/config/SRPConfig";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpWeaknessBridge";
    static final String[] FIELDS = { "parasiteWeakToItems", "parasiteWeakToMobs", "parasiteWeakToElse" };

    @Override
    public byte[] transform(String name, byte[] bytes, TargetSpec spec) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode damage = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("func_70097_a")
                && method.desc.equals("(Lnet/minecraft/util/DamageSource;F)Z")) damage = method;
        }
        if (damage == null) throw new IllegalStateException("SRP damage method missing");
        for (int i = 0; i < FIELDS.length; i++) replaceLoop(damage, i);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void replaceLoop(MethodNode method, int category) {
        FieldInsnNode lengthField = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.GETSTATIC && CONFIG.equals(field.owner)
                    && FIELDS[category].equals(field.name) && "[Ljava/lang/String;".equals(field.desc)
                    && next(field).getOpcode() == Opcodes.ARRAYLENGTH
                    && next(next(field)).getOpcode() == Opcodes.IF_ICMPGE) {
                    if (lengthField != null) throw new IllegalStateException("Ambiguous SRP weakness loop");
                    lengthField = field;
                }
            }
        }
        if (lengthField == null) throw new IllegalStateException("SRP weakness loop missing: " + category);
        AbstractInsnNode indexLoad = previous(lengthField);
        AbstractInsnNode indexStore = previous(indexLoad);
        AbstractInsnNode start = previous(indexStore);
        AbstractInsnNode limitJump = next(next(lengthField));
        if (!(indexLoad instanceof VarInsnNode) || indexLoad.getOpcode() != Opcodes.ILOAD
            || !(indexStore instanceof VarInsnNode) || indexStore.getOpcode() != Opcodes.ISTORE
            || ((VarInsnNode) indexLoad).var != ((VarInsnNode) indexStore).var
            || start.getOpcode() != Opcodes.ICONST_0 || limitJump.getOpcode() != Opcodes.IF_ICMPGE) {
            throw new IllegalStateException("SRP weakness loop shape changed");
        }
        LabelNode exit = ((JumpInsnNode) limitJump).label;
        AbstractInsnNode last = previous(exit);
        if (last.getOpcode() != Opcodes.GOTO || next(((JumpInsnNode) last).label) != indexLoad)
            throw new IllegalStateException("SRP weakness back edge changed");
        int split = 0, parse = 0, multiply = 0;
        int damageLocal = -1, sourceLocal = -1;
        for (AbstractInsnNode instruction = start; instruction != last; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("java/lang/String") && call.name.equals("split")) split++;
                if (call.owner.equals("java/lang/Float") && call.name.equals("parseFloat")) parse++;
                if (call.owner.equals("java/lang/String") && call.name.equals("equals")) {
                    AbstractInsnNode load = previous(call);
                    if (load.getOpcode() == Opcodes.ALOAD) sourceLocal = ((VarInsnNode) load).var;
                }
            }
            if (instruction.getOpcode() == Opcodes.FMUL) {
                multiply++;
                AbstractInsnNode store = next(instruction);
                if (store.getOpcode() == Opcodes.FSTORE) damageLocal = ((VarInsnNode) store).var;
            }
        }
        if (split != 1 || parse != 1 || multiply != 1 || damageLocal != 2 || sourceLocal < 0)
            throw new IllegalStateException("SRP weakness arithmetic changed");
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.FLOAD, damageLocal));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, CONFIG, FIELDS[category], "[Ljava/lang/String;"));
        replacement.add(new InsnNode(Opcodes.ICONST_0 + category));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "apply",
            "(FLjava/lang/String;[Ljava/lang/String;I)F", false));
        replacement.add(new VarInsnNode(Opcodes.FSTORE, damageLocal));
        method.instructions.insertBefore(start, replacement);
        AbstractInsnNode after = last.getNext();
        for (AbstractInsnNode instruction = start; instruction != after;) {
            AbstractInsnNode following = instruction.getNext();
            // Preserve label identities used by debugging metadata and surrounding scopes.
            if (!(instruction instanceof LabelNode)) method.instructions.remove(instruction);
            instruction = following;
        }
    }

    private static AbstractInsnNode next(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}
