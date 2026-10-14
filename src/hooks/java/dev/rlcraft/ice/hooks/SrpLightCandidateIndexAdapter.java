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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Maintains candidate bits at actual storage mutations, with no periodic rescans. */
final class SrpLightCandidateIndexAdapter implements OptimizerBytecodeAdapter {
    static final String SECTION = "net/minecraft/world/chunk/BlockStateContainer";
    static final String BLOCK = "net/minecraft/block/Block";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/srp/SrpLightSectionAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpLightCandidateIndex";
    private static final String FIELD = "ice$lightCandidates";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (SECTION.equals(node.name)) section(node);
        else if (BLOCK.equals(node.name)) block(node);
        else throw new IllegalStateException("Unexpected light index target " + node.name);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void section(ClassNode node) {
        if (node.interfaces.contains(ACCESS)) throw new IllegalStateException("Light index already injected");
        node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            FIELD, "Ljava/lang/Object;", null, null));
        int setters = 0, loaders = 0;
        for (MethodNode method : node.methods) {
            if ("(ILnet/minecraft/block/state/IBlockState;)V".equals(method.desc)) {
                setters++;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList changed = new InsnList();
                    changed.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    changed.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    changed.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    changed.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "changed",
                        "(L" + SECTION + ";ILnet/minecraft/block/state/IBlockState;)V", false));
                    method.instructions.insertBefore(insn, changed);
                }
            }
            if (("(Lnet/minecraft/network/PacketBuffer;)V".equals(method.desc)
                && ("read".equals(method.name) || "func_186010_a".equals(method.name)))
                || "([BLnet/minecraft/world/chunk/NibbleArray;Lnet/minecraft/world/chunk/NibbleArray;)V".equals(method.desc)) {
                loaders++;
                InsnList clear = new InsnList();
                clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
                clear.add(new InsnNode(Opcodes.ACONST_NULL));
                clear.add(new FieldInsnNode(Opcodes.PUTFIELD, SECTION, FIELD, "Ljava/lang/Object;"));
                method.instructions.insert(clear);
            }
        }
        if (setters != 1 || loaders != 2) throw new IllegalStateException("Light section mutation graph changed");
        MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FIELD, "()Ljava/lang/Object;", null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, SECTION, FIELD, "Ljava/lang/Object;"));
        get.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(get);
        MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FIELD, "(Ljava/lang/Object;)V", null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, SECTION, FIELD, "Ljava/lang/Object;"));
        set.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(set);
    }

    private static void block(ClassNode node) {
        int methods = 0;
        for (MethodNode method : node.methods) {
            if (!"(F)Lnet/minecraft/block/Block;".equals(method.desc)
                || !("setLightLevel".equals(method.name) || "func_149715_a".equals(method.name))) continue;
            methods++;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.ARETURN) method.instructions.insertBefore(insn,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "blockLightChanged", "()V", false));
            }
        }
        if (methods != 1) throw new IllegalStateException("Block light-level setter changed");
    }
}
