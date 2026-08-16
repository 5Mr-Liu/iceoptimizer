package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes repeated immutable-map and AABB construction from Rustic lattices. */
final class RusticLatticeAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "rustic/common/blocks/BlockLattice";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/rustic/RusticLatticeBridge";
    static final String EXTENDED_STATE =
        "net/minecraftforge/common/property/IExtendedBlockState";
    static final String UNLISTED_PROPERTY =
        "net/minecraftforge/common/property/IUnlistedProperty";
    static final String STATE = "net/minecraft/block/state/IBlockState";
    static final String ACCESS = "net/minecraft/world/IBlockAccess";
    static final String POS = "net/minecraft/util/math/BlockPos";
    static final String AABB = "net/minecraft/util/math/AxisAlignedBB";
    static final String EXTENDED_DESCRIPTOR = "(L" + STATE + ";L" + ACCESS + ";L" + POS + ";)L" + STATE + ";";
    static final String BOUNDING_DESCRIPTOR = "(L" + STATE + ";L" + ACCESS + ";L" + POS + ";)L" + AABB + ";";
    static final String ORIGINAL_BOUNDING = "ice$originalBoundingBox";
    private static final String CONNECTIONS = "CONNECTIONS";
    private static final String CONNECTIONS_DESCRIPTOR =
        "[Lrustic/common/blocks/properties/UnlistedPropertyBool;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("Rustic 栅栏目标变化：" + node.name);
        }
        MethodNode extended = requireMethod(node, "getExtendedState", EXTENDED_DESCRIPTOR);
        replaceWithProperty(extended);
        MethodNode bounding = requireMethodByDescriptor(node, BOUNDING_DESCRIPTOR);
        wrapBoundingBox(node, bounding);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void replaceWithProperty(MethodNode method) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE
                && EXTENDED_STATE.equals(call.owner) && "withProperty".equals(call.name)
                && ("(L" + UNLISTED_PROPERTY + ";Ljava/lang/Object;)L"
                    + EXTENDED_STATE + ";").equals(call.desc)) {
                found = call;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("Rustic withProperty 调用数量变化：" + count);
        method.instructions.set(found, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "withProperty", "(L" + EXTENDED_STATE + ";L" + UNLISTED_PROPERTY
                + ";Ljava/lang/Object;)L" + EXTENDED_STATE + ";", false));
    }

    private static void wrapBoundingBox(ClassNode node, MethodNode original) {
        rejectMethod(node, ORIGINAL_BOUNDING, original.desc);
        int access = original.access;
        String originalName = original.name;
        String signature = original.signature;
        String[] exceptions = exceptions(original);
        original.name = ORIGINAL_BOUNDING;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, originalName,
            BOUNDING_DESCRIPTOR, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "isEnabled", "()Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
            "getExtendedState", EXTENDED_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        wrapper.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, EXTENDED_STATE));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, EXTENDED_STATE));
        wrapper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name,
            CONNECTIONS, CONNECTIONS_DESCRIPTOR));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "boundingBox", "(L" + EXTENDED_STATE + ";[Ljava/lang/Object;)L" + AABB + ";", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name,
            ORIGINAL_BOUNDING, BOUNDING_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(wrapper);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                found = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(node.name + " 方法数量变化："
            + name + descriptor + '=' + count);
        return found;
    }

    private static MethodNode requireMethodByDescriptor(ClassNode node, String descriptor) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (descriptor.equals(method.desc)) {
                found = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(node.name
            + " 包围盒方法数量变化：" + count);
        return found;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 ICE 方法 " + name + descriptor);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String[] exceptions(MethodNode method) {
        List<String> values = method.exceptions;
        return values == null ? null : values.toArray(new String[values.size()]);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
