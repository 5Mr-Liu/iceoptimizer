package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Exposes the original class index, without replacing its add/remove machinery. */
final class SrpArchitectBucketAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/util/ClassInheritanceMultiMap";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/srp/SrpArchitectBucketAccess";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5); reader.accept(node, 0);
        String buckets = null, resolve = null;
        for (FieldNode field : node.fields)
            if ("Ljava/util/Map;".equals(field.desc) && ("map".equals(field.name) || "field_180218_a".equals(field.name))) buckets = field.name;
        for (MethodNode method : node.methods)
            if ("(Ljava/lang/Class;)Ljava/lang/Class;".equals(method.desc)
                && ("initializeClassLookup".equals(method.name) || "func_181157_b".equals(method.name))) resolve = method.name;
        if (buckets == null || resolve == null) throw new IllegalStateException("Entity class bucket layout changed");
        node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "ice$architectClass", "Ljava/lang/Class;", null, null));
        MethodNode map = method("ice$classBuckets", "()Ljava/util/Map;");
        map.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        map.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, buckets, "Ljava/util/Map;"));
        map.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(map);
        MethodNode lookup = method("ice$resolveEntityClass", "(Ljava/lang/Class;)Ljava/lang/Class;");
        lookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); lookup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        lookup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, resolve, lookup.desc, false));
        lookup.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(lookup);
        MethodNode get = method("ice$architectClass", "()Ljava/lang/Class;");
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "ice$architectClass", "Ljava/lang/Class;"));
        get.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(get);
        MethodNode set = method("ice$architectClass", "(Ljava/lang/Class;)V");
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "ice$architectClass", "Ljava/lang/Class;"));
        set.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(set);
        return ServerModAdapterSupport.write(reader, node);
    }
    private static MethodNode method(String name, String descriptor) {
        return new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
    }
}
