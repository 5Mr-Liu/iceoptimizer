package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Save-only accessors and a retained original section serializer. */
final class ChunkSectionNbtAdapter implements OptimizerBytecodeAdapter {
    enum Part { SECTION, ID_MAP }
    static final String SECTION = "net/minecraft/world/chunk/BlockStateContainer";
    static final String ID_MAP = "net/minecraft/util/ObjectIntIdentityMap";
    static final String NIBBLE = "net/minecraft/world/chunk/NibbleArray";
    static final String DESC = "([BL" + NIBBLE + ";)L" + NIBBLE + ";";
    private static final String PREFIX = "dev/rlcraft/ice/optimizer/compat/save/";
    private final Part part;

    ChunkSectionNbtAdapter(Part part) { this.part = part; }

    @Override public byte[] transform(String transformedName, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        String expected = part == Part.SECTION ? SECTION : ID_MAP;
        if (!expected.equals(node.name) || !expected.equals(transformedName.replace('.', '/'))) {
            throw new IllegalStateException("Unexpected chunk NBT target " + node.name);
        }
        if (part == Part.SECTION) section(node); else idMap(node);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void section(ClassNode node) {
        String abi = PREFIX + "ChunkSectionNbtAccess";
        if (node.interfaces.contains(abi)) throw new IllegalStateException("Section ABI already installed");
        MethodNode original = requireMethod(node, "func_186017_a", DESC);
        int registryGets = 0;
        int nibbleSets = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : original.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (ID_MAP.equals(call.owner) && "func_148747_b".equals(call.name)) registryGets++;
            if (NIBBLE.equals(call.owner) && "func_76581_a".equals(call.name)) nibbleSets++;
        }
        if (registryGets != 1 || nibbleSets != 2) {
            throw new IllegalStateException("Section NBT serialization graph changed");
        }
        original.name = "ice$originalSectionNbt";
        original.access |= Opcodes.ACC_SYNTHETIC;
        node.interfaces.add(abi);
        getter(node, "ice$nbtStorage", "field_186021_b", "Lnet/minecraft/util/BitArray;", Opcodes.ARETURN);
        getter(node, "ice$nbtPalette", "field_186022_c", "Lnet/minecraft/world/chunk/IBlockStatePalette;", Opcodes.ARETURN);
        getter(node, "ice$nbtBits", "field_186024_e", "I", Opcodes.IRETURN);
        MethodNode air = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$nbtAir", "()Lnet/minecraft/block/state/IBlockState;", null, null);
        air.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, SECTION,
            "field_186020_a", "Lnet/minecraft/block/state/IBlockState;"));
        air.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(air);
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC,
            "func_186017_a", DESC, original.signature, null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            PREFIX + "ChunkSectionNbtBridge", "write", "(L" + SECTION + ";[BL" + NIBBLE
                + ";)L" + NIBBLE + ";", false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(wrapper);
    }

    private static void idMap(ClassNode node) {
        String abi = PREFIX + "BlockStateIdMapAccess";
        if (node.interfaces.contains(abi)) throw new IllegalStateException("ID map ABI already installed");
        requireMethod(node, "func_148747_b", "(Ljava/lang/Object;)I");
        node.interfaces.add(abi);
        getter(node, "ice$directStateIds", "field_148749_a", "Ljava/util/IdentityHashMap;", Opcodes.ARETURN);
    }

    private static void getter(ClassNode node, String method, String field, String desc, int result) {
        boolean found = false;
        for (FieldNode value : node.fields) {
            if (field.equals(value.name) && desc.equals(value.desc)) found = true;
        }
        if (!found) throw new IllegalStateException("Missing save accessor field " + field);
        MethodNode getter = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            method, "()" + desc, null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, desc));
        getter.instructions.add(new InsnNode(result));
        node.methods.add(getter);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String desc) {
        MethodNode match = null;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                if (match != null) throw new IllegalStateException("Duplicate save method " + name);
                match = method;
            }
        }
        if (match == null) throw new IllegalStateException("Missing save method " + name);
        return match;
    }
}
