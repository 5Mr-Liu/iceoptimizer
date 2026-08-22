package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds a stable-file snapshot path ahead of OTG's original settings reader. */
final class OtgFileSettingsReaderAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/pg85/otg/configuration/io/FileSettingsReaderOTGPlus";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/otg/OtgSynchronousIoBridge";
    static final String ORIGINAL = "ice$readSettingsOriginal";
    static final String READ_DESCRIPTOR =
        "(Ljava/lang/Object;Ljava/io/File;Ljava/lang/Class;)Z";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG FileSettingsReaderOTGPlus 类名变化：" + node.name);
        }

        MethodNode original = null;
        for (MethodNode method : node.methods) {
            if (ORIGINAL.equals(method.name)) {
                throw new IllegalStateException("OTG settings 已存在 ICE 备份方法");
            }
            if ("readSettings".equals(method.name) && "()V".equals(method.desc)) {
                if (original != null) throw new IllegalStateException("OTG readSettings 重载变化");
                original = method;
            }
        }
        if (original == null) throw new IllegalStateException("OTG readSettings()V 缺失");
        int wrapperAccess = original.access;
        original.name = ORIGINAL;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, wrapperAccess,
            "readSettings", "()V", original.signature,
            original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
            "file", "Ljava/io/File;"));
        wrapper.instructions.add(new LdcInsnNode(Type.getObjectType(node.name)));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "readSettings", READ_DESCRIPTOR, false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name,
            ORIGINAL, "()V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
