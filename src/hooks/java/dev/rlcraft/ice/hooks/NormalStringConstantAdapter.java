package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Cache ownership follows Internal creation/purge; outer locking and dedup counters stay original. */
final class NormalStringConstantAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "mirror/normalasm/api/NormalStringPool$Internal";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/ConstantNbtKeyBridge";
    static final String FIELD = "ice$constantNbtKeys";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FIELD, "[Ljava/lang/String;", null, null));
        int constructors = 0, calls = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if ("<init>".equals(method.name) && instruction.getOpcode() == Opcodes.RETURN) {
                    InsnList init = new InsnList();
                    init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.add(new IntInsnNode(Opcodes.BIPUSH, 6));
                    init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
                    init.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET, FIELD, "[Ljava/lang/String;"));
                    method.instructions.insertBefore(instruction, init);
                    constructors++;
                }
                if (!"addOrGet".equals(method.name) || !(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!"it/unimi/dsi/fastutil/objects/ObjectOpenHashSet".equals(call.owner) || !"addOrGet".equals(call.name)) continue;
                InsnList cache = new InsnList();
                cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
                cache.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, FIELD, "[Ljava/lang/String;"));
                method.instructions.insertBefore(call, cache);
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "addOrGet",
                    "(Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Object;", false));
                calls++;
            }
        }
        if (constructors != 1 || calls != 1) throw new IllegalStateException("NormalStringPool ownership shape changed");
        return ServerModAdapterSupport.write(reader, node);
    }
}
