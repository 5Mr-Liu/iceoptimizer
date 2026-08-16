package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Installs a mutation-aware index on Lycanites' standard blockIds lists. */
final class LycanitesBlockMembershipAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "com/lycanitesmobs/core/spawner/location/BlockSpawnLocation";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesBlockMembershipBridge";
    static final String TRACK_DESCRIPTOR = "(Ljava/util/List;)Ljava/util/List;";
    private static final String VALID_METHOD = "isValidBlock";
    private static final String VALID_DESCRIPTOR =
        "(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Z";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("Lycanites blockIds 目标类变化：" + node.name);
        }
        requireField(node, "blockIds", "Ljava/util/List;");
        MethodNode valid = requireMethod(node, VALID_METHOD, VALID_DESCRIPTOR);
        if (countCalls(valid, Opcodes.INVOKEINTERFACE, "java/util/List", "contains",
            "(Ljava/lang/Object;)Z") != 2) {
            throw new IllegalStateException("Lycanites blockIds contains 调用图变化");
        }
        if (countCalls(node, Opcodes.INVOKESTATIC, BRIDGE, "track", TRACK_DESCRIPTOR) != 0) {
            throw new IllegalStateException("Lycanites blockIds 索引已安装");
        }

        List<FieldWrite> writes = fieldWrites(node, Opcodes.PUTFIELD, TARGET,
            "blockIds", "Ljava/util/List;");
        if (writes.isEmpty() || writes.size() > 4) {
            throw new IllegalStateException("Lycanites blockIds 初始化写入数量变化：" + writes.size());
        }
        for (FieldWrite write : writes) {
            if ((write.method.access & Opcodes.ACC_STATIC) != 0) {
                throw new IllegalStateException("Lycanites blockIds 写入不再位于实例方法");
            }
            InsnList tracking = new InsnList();
            tracking.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tracking.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tracking.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET,
                "blockIds", "Ljava/util/List;"));
            tracking.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                "track", TRACK_DESCRIPTOR, false));
            tracking.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET,
                "blockIds", "Ljava/util/List;"));
            write.method.instructions.insert(write.instruction, tracking);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static List<FieldWrite> fieldWrites(ClassNode node, int opcode, String owner,
                                                 String name, String descriptor) {
        List<FieldWrite> result = new ArrayList<FieldWrite>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode)) continue;
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name) && descriptor.equals(field.desc)) {
                    result.add(new FieldWrite(method, field));
                }
            }
        }
        return result;
    }

    private static int countCalls(MethodNode method, int opcode, String owner,
                                  String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == opcode && owner.equals(call.owner)
                && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countCalls(ClassNode node, int opcode, String owner,
                                  String name, String descriptor) {
        int count = 0;
        for (MethodNode method : node.methods) {
            count += countCalls(method, opcode, owner, name, descriptor);
        }
        return count;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode result = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                result = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("Lycanites 方法数量变化："
            + name + descriptor + '=' + count);
        return result;
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        int count = 0;
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) count++;
        }
        if (count != 1) throw new IllegalStateException("Lycanites 字段数量变化："
            + name + descriptor + '=' + count);
    }

    private static final class FieldWrite {
        private final MethodNode method;
        private final FieldInsnNode instruction;

        private FieldWrite(MethodNode method, FieldInsnNode instruction) {
            this.method = method;
            this.instruction = instruction;
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
