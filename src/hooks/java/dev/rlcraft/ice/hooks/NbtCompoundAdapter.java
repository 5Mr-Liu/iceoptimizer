package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Compound access plus entry iteration; the actual per-tag encoder remains vanilla. */
final class NbtCompoundAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/nbt/NBTTagCompound";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/nbt/NbtCompoundAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/NbtEncodingBridge";
    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET.equals(node.name) || node.interfaces.contains(ACCESS)) throw new IllegalStateException("NBT compound target changed");
        String fieldName = null;
        for (FieldNode field : node.fields) if ("Ljava/util/Map;".equals(field.desc)
            && ("field_74784_a".equals(field.name) || "tagMap".equals(field.name))) fieldName = field.name;
        MethodNode write = null;
        for (MethodNode method : node.methods) if ("(Ljava/io/DataOutput;)V".equals(method.desc)
            && ("func_74734_a".equals(method.name) || "write".equals(method.name))) write = method;
        if (fieldName == null || write == null) throw new IllegalStateException("NBT compound write/map missing");
        MethodInsnNode entryCall = null;
        for (AbstractInsnNode instruction : write.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKESTATIC && TARGET.equals(call.owner)
                && "(Ljava/lang/String;Lnet/minecraft/nbt/NBTBase;Ljava/io/DataOutput;)V".equals(call.desc)) entryCall = call;
        }
        if (entryCall == null) throw new IllegalStateException("NBT entry encoder missing");
        node.interfaces.add(ACCESS);
        MethodNode map = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$tagMap", "()Ljava/util/Map;", null, null);
        map.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        map.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, fieldName, "Ljava/util/Map;"));
        map.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(map);
        MethodNode entry = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$writeNbtEntry", entryCall.desc, null, new String[] { "java/io/IOException" });
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        entry.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, entryCall.name, entryCall.desc, false));
        entry.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(entry);
        String originalName = write.name;
        int originalAccess = write.access;
        write.name = "ice$originalNbtWrite";
        write.access = (write.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, originalAccess, originalName, write.desc,
            write.signature, new String[] { "java/io/IOException" });
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "write",
            "(L" + TARGET + ";Ljava/io/DataOutput;)V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
