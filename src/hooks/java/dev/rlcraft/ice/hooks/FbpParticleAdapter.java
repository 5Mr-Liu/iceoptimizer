package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact FBP 2.4.x internal Tessellator flush/restart boundary adapter. */
final class FbpParticleAdapter implements OptimizerBytecodeAdapter {
    static final String BLOCK = "com/TominoCZ/FBP/particle/FBPParticleBlock";
    static final String FLAME = "com/TominoCZ/FBP/particle/FBPParticleFlame";
    static final String PARTICLE = "net/minecraft/client/particle/Particle";
    static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder";
    static final String ENTITY = "net/minecraft/entity/Entity";
    static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    static final String VERTEX_FORMAT =
        "net/minecraft/client/renderer/vertex/VertexFormat";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/particle/FbpParticleRenderBridge";
    static final String RENDER = "func_180434_a";
    static final String RENDER_DESC = "(L" + BUFFER + ";L" + ENTITY + ";FFFFFF)V";
    static final String ORIGINAL = "ice$renderParticleOriginal";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass,
                            TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!BLOCK.equals(node.name) && !FLAME.equals(node.name)) {
            throw new IllegalStateException("unknown FBP particle " + node.name);
        }
        MethodNode render = null;
        int methods = 0;
        for (MethodNode candidate : node.methods) {
            if (RENDER.equals(candidate.name) && RENDER_DESC.equals(candidate.desc)) {
                render = candidate;
                methods++;
            }
        }
        if (methods != 1 || render == null) {
            throw new IllegalStateException("FBP render method changed: " + methods);
        }
        if ((render.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(
                "FBP render method is not a concrete instance method");
        }
        int draws = 0;
        int begins = 0;
        StringBuilder boundaryOrder = new StringBuilder(4);
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && TESSELLATOR.equals(call.owner)
                && "func_78381_a".equals(call.name) && "()V".equals(call.desc)) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = "draw";
                call.desc = "(L" + TESSELLATOR + ";)V";
                call.itf = false;
                draws++;
                boundaryOrder.append('D');
            } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && BUFFER.equals(call.owner)
                && "func_181668_a".equals(call.name)
                && ("(IL" + VERTEX_FORMAT + ";)V").equals(call.desc)) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = "begin";
                call.desc = "(L" + BUFFER + ";IL" + VERTEX_FORMAT + ";)V";
                call.itf = false;
                begins++;
                boundaryOrder.append('B');
            }
        }
        if (draws != 2 || begins != 2 || !"DBDB".contentEquals(boundaryOrder)) {
            throw new IllegalStateException("FBP internal boundary graph changed: draw="
                + draws + ", begin=" + begins + ", order=" + boundaryOrder);
        }
        if (findMethod(node, ORIGINAL, RENDER_DESC) != null) {
            throw new IllegalStateException("FBP particle already adapted");
        }
        int access = render.access;
        String signature = render.signature;
        List<String> exceptions = render.exceptions;
        render.name = ORIGINAL;
        render.access = (render.access & ~(Opcodes.ACC_PUBLIC
            | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_SYNTHETIC;
        node.methods.add(wrapper(node.name, access, signature, exceptions));
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode wrapper(String owner, int access, String signature,
                                      List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, RENDER,
            RENDER_DESC, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "enter",
            "(Ljava/lang/Object;)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 9);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        for (int local = 3; local <= 8; local++) {
            code.visitVarInsn(Opcodes.FLOAD, local);
        }
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, ORIGINAL,
            RENDER_DESC, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 9);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "exit", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 11);
        code.visitVarInsn(Opcodes.LLOAD, 9);
        code.visitVarInsn(Opcodes.ALOAD, 11);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "abort",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 11);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static MethodNode findMethod(ClassNode node, String name,
                                         String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
