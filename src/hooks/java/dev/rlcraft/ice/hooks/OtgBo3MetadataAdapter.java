package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Installs stable BO3 metadata caching and the linear NBT reader. */
final class OtgBo3MetadataAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/pg85/otg/customobjects/bo3/BO3Loader";
    static final String TAG = "com/pg85/otg/util/bo3/NamedBinaryTag";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/otg/OtgSynchronousIoBridge";
    static final String CREATE_MAP_DESCRIPTOR = "(Ljava/lang/Class;)Ljava/util/Map;";
    static final String READ_DESCRIPTOR =
        "(Ljava/io/InputStream;ZLjava/lang/Class;)Ljava/lang/Object;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG BO3Loader 类名变化：" + node.name);
        }

        installMetadataMap(node);
        installLinearReaders(node);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void installMetadataMap(ClassNode node) {
        MethodNode initializer = findMethod(node, "<clinit>", "()V");
        TypeInsnNode allocation = null;
        AbstractInsnNode duplicate = null;
        MethodInsnNode constructor = null;
        FieldInsnNode store = null;
        int matches = 0;
        for (AbstractInsnNode instruction : initializer.instructions.toArray()) {
            if (!(instruction instanceof TypeInsnNode)
                || instruction.getOpcode() != Opcodes.NEW
                || !"java/util/HashMap".equals(((TypeInsnNode) instruction).desc)) continue;
            AbstractInsnNode next = nextOpcode(instruction);
            AbstractInsnNode after = nextOpcode(next);
            AbstractInsnNode finalInstruction = nextOpcode(after);
            if (next == null || next.getOpcode() != Opcodes.DUP
                || !(after instanceof MethodInsnNode)
                || after.getOpcode() != Opcodes.INVOKESPECIAL
                || !"java/util/HashMap".equals(((MethodInsnNode) after).owner)
                || !"<init>".equals(((MethodInsnNode) after).name)
                || !"()V".equals(((MethodInsnNode) after).desc)
                || !(finalInstruction instanceof FieldInsnNode)
                || finalInstruction.getOpcode() != Opcodes.PUTSTATIC
                || !node.name.equals(((FieldInsnNode) finalInstruction).owner)
                || !"LoadedTags".equals(((FieldInsnNode) finalInstruction).name)
                || !"Ljava/util/Map;".equals(((FieldInsnNode) finalInstruction).desc)) continue;
            allocation = (TypeInsnNode) instruction;
            duplicate = next;
            constructor = (MethodInsnNode) after;
            store = (FieldInsnNode) finalInstruction;
            matches++;
        }
        if (matches != 1 || allocation == null || duplicate == null
            || constructor == null || store == null) {
            throw new IllegalStateException("OTG BO3Loader LoadedTags 初始化图变化，匹配 " + matches);
        }

        InsnList replacement = new InsnList();
        replacement.add(new LdcInsnNode(Type.getObjectType(TAG)));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "createMetadataMap", CREATE_MAP_DESCRIPTOR, false));
        initializer.instructions.insertBefore(allocation, replacement);
        initializer.instructions.remove(allocation);
        initializer.instructions.remove(duplicate);
        initializer.instructions.remove(constructor);
    }

    private static void installLinearReaders(ClassNode node) {
        MethodNode loader = findMethod(node, "loadTileEntityFromNBT",
            "(Ljava/lang/String;)L" + TAG + ";");
        int replacements = 0;
        for (AbstractInsnNode instruction : loader.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKESTATIC || !TAG.equals(call.owner)
                || !"readFrom".equals(call.name)
                || !"(Ljava/io/InputStream;Z)L".concat(TAG).concat(";").equals(call.desc)) {
                continue;
            }
            loader.instructions.insertBefore(call, new LdcInsnNode(Type.getObjectType(TAG)));
            MethodInsnNode bridge = new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                "readNamedBinaryTag", READ_DESCRIPTOR, false);
            loader.instructions.set(call, bridge);
            loader.instructions.insert(bridge, new TypeInsnNode(Opcodes.CHECKCAST, TAG));
            replacements++;
        }
        if (replacements != 2) {
            throw new IllegalStateException("OTG BO3Loader NBT readFrom 调用应为 2，实际 "
                + replacements);
        }
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        MethodNode result = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                result = method;
                matches++;
            }
        }
        if (matches != 1 || result == null) {
            throw new IllegalStateException("OTG BO3Loader " + name + descriptor
                + " 匹配应为 1，实际 " + matches);
        }
        return result;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
