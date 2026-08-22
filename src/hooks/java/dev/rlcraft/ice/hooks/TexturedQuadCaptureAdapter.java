package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Copies each completed vanilla quad before Tessellator resets BufferBuilder. */
final class TexturedQuadCaptureAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/model/TexturedQuad";
    static final String METHOD = "func_178765_a";
    static final String DESCRIPTOR =
        "(Lnet/minecraft/client/renderer/BufferBuilder;F)V";
    static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelMeshCaptureBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("TexturedQuad target changed: " + node.name);
        }
        MethodNode method = null;
        int methods = 0;
        for (MethodNode candidate : node.methods) {
            if (METHOD.equals(candidate.name) && DESCRIPTOR.equals(candidate.desc)) {
                method = candidate;
                methods++;
            }
        }
        if (methods != 1 || method == null) {
            throw new IllegalStateException("TexturedQuad render method changed: " + methods);
        }
        MethodInsnNode draw = null;
        int draws = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && TESSELLATOR.equals(call.owner)
                && "func_78381_a".equals(call.name) && "()V".equals(call.desc)) {
                draw = call;
                draws++;
            }
        }
        if (draws != 1 || draw == null) {
            throw new IllegalStateException("TexturedQuad Tessellator graph changed: " + draws);
        }
        InsnList capture = new InsnList();
        capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
        capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "captureQuad",
            "(Lnet/minecraft/client/renderer/BufferBuilder;)V", false));
        method.instructions.insertBefore(draw, capture);
        ClassWriter writer = new ClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }
}
