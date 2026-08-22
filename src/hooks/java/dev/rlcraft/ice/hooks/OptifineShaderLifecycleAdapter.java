package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact OptiFine G5-compatible Program lifecycle wrapper. */
final class OptifineShaderLifecycleAdapter implements OptimizerBytecodeAdapter {
    static final String SHADERS = "net/optifine/shaders/Shaders";
    static final String PROGRAM = "net/optifine/shaders/Program";
    static final String USE_PROGRAM = "useProgram";
    static final String USE_DESC = "(L" + PROGRAM + ";)V";
    static final String ORIGINAL = "ice$useProgramOriginal";
    static final String BOOTSTRAP = "dev/rlcraft/ice/hooks/OptifineShaderBootstrap";
    private static final String ARB_SHADER =
        "org/lwjgl/opengl/ARBShaderObjects";

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!SHADERS.equals(node.name)) {
            throw new IllegalStateException("OptiFine Shaders target changed");
        }
        if (find(node, ORIGINAL, USE_DESC) != null) {
            throw new IllegalStateException("OptiFine useProgram already adapted");
        }
        MethodNode original = require(node, USE_PROGRAM, USE_DESC);
        if ((original.access & Opcodes.ACC_STATIC) == 0
            || (original.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("OptiFine useProgram shape changed");
        }
        validateGraph(original);
        int access = original.access;
        String signature = original.signature;
        List<String> exceptions = original.exceptions;
        original.name = ORIGINAL;
        original.access = (original.access
            & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_SYNCHRONIZED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(wrapper(access, signature, exceptions));
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void validateGraph(MethodNode method) {
        int getId = 0;
        int use = 0;
        int drawBuffers = 0;
        int activeProgram = 0;
        int activeProgramId = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (PROGRAM.equals(call.owner) && "getId".equals(call.name)
                    && "()I".equals(call.desc)) getId++;
                if (ARB_SHADER.equals(call.owner)
                    && "glUseProgramObjectARB".equals(call.name)
                    && "(I)V".equals(call.desc)) use++;
                if (SHADERS.equals(call.owner) && "setDrawBuffers".equals(call.name)
                    && "(Ljava/nio/IntBuffer;)V".equals(call.desc)) drawBuffers++;
            } else if (instruction instanceof FieldInsnNode
                && instruction.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (SHADERS.equals(field.owner) && "activeProgram".equals(field.name)
                    && ("L" + PROGRAM + ";").equals(field.desc)) activeProgram++;
                if (SHADERS.equals(field.owner) && "activeProgramID".equals(field.name)
                    && "I".equals(field.desc)) activeProgramId++;
            }
        }
        if (getId != 2 || use != 2 || drawBuffers != 1
            || activeProgram != 1 || activeProgramId != 2) {
            throw new IllegalStateException("OptiFine useProgram graph changed: "
                + getId + '/' + use + '/' + drawBuffers + '/' + activeProgram
                + '/' + activeProgramId);
        }
    }

    private static MethodNode wrapper(int access, String signature,
                                      List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, USE_PROGRAM,
            USE_DESC, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "begin",
            "(Ljava/lang/Object;)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 1);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, SHADERS, ORIGINAL,
            USE_DESC, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "end",
            "(JLjava/lang/Object;)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 3);
        code.visitVarInsn(Opcodes.LLOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "abort",
            "(JLjava/lang/Object;Ljava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static MethodNode require(ClassNode node, String name, String desc) {
        MethodNode method = find(node, name, desc);
        if (method == null) throw new IllegalStateException("missing " + name + desc);
        return method;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException("duplicate " + name + desc);
            found = method;
        }
        return found;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
