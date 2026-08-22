package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Publishes ActiveRenderInfo's already-read matrices without another glGet. */
final class RenderMatrixAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/renderer/ActiveRenderInfo";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/render/visibility/RenderMatrixBridge";
    private static final String MODEL_VIEW = "field_178812_b";
    private static final String PROJECTION = "field_178813_c";
    private static final String VIEWPORT = "field_178814_a";
    private static final String ENTITY_DESC = "(Lnet/minecraft/entity/Entity;Z)V";
    private static final String PLAYER_DESC = "(Lnet/minecraft/entity/player/EntityPlayer;Z)V";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("matrix target " + node.name);
        requireField(node, MODEL_VIEW, "Ljava/nio/FloatBuffer;");
        requireField(node, PROJECTION, "Ljava/nio/FloatBuffer;");
        requireField(node, VIEWPORT, "Ljava/nio/IntBuffer;");
        MethodNode method = findByDescriptor(node, ENTITY_DESC);
        if (method == null) method = findByDescriptor(node, PLAYER_DESC);
        if (method == null) throw new IllegalStateException("ActiveRenderInfo update descriptor");
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList publish = new InsnList();
            publish.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, MODEL_VIEW,
                "Ljava/nio/FloatBuffer;"));
            publish.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, PROJECTION,
                "Ljava/nio/FloatBuffer;"));
            publish.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, VIEWPORT,
                "Ljava/nio/IntBuffer;"));
            publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "capture",
                "(Ljava/nio/FloatBuffer;Ljava/nio/FloatBuffer;Ljava/nio/IntBuffer;)V", false));
            method.instructions.insertBefore(instruction, publish);
            returns++;
        }
        if (returns != 1) throw new IllegalStateException("ActiveRenderInfo return count " + returns);
        ClassWriter writer = new SafeWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        for (org.objectweb.asm.tree.FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) return;
        }
        throw new IllegalStateException("matrix field " + name);
    }

    private static MethodNode findByDescriptor(ClassNode node, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!descriptor.equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
            if (found != null) throw new IllegalStateException("duplicate matrix update descriptor");
            found = method;
        }
        return found;
    }

    private static final class SafeWriter extends ClassWriter {
        private SafeWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
