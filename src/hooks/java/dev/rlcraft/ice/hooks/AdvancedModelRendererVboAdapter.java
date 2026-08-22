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

/** LLibrary owns a second private display-list/compiler pair. */
final class AdvancedModelRendererVboAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer";
    static final String COMPILE = "compileDisplayList";
    static final String ORIGINAL = "rlcraftIce$compileDisplayListOriginal";
    static final String DESCRIPTOR = "(F)V";
    static final String GL_STATE_MANAGER =
        "net/minecraft/client/renderer/GlStateManager";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelMeshCaptureBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass,
                            TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("AdvancedModelRenderer target changed: "
                + node.name);
        }
        MethodNode compile = null;
        int compilers = 0;
        int emitters = 0;
        for (MethodNode method : node.methods) {
            if (COMPILE.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                compile = method;
                compilers++;
            }
            if (ORIGINAL.equals(method.name)) {
                throw new IllegalStateException("AdvancedModelRenderer already adapted");
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && GL_STATE_MANAGER.equals(call.owner)
                    && "func_179148_o".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                    call.owner = BRIDGE;
                    call.name = "callList";
                    emitters++;
                }
            }
        }
        if (compilers != 1 || compile == null || emitters != 1
            || (compile.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("AdvancedModelRenderer graph changed: compile="
                + compilers + ", emitters=" + emitters);
        }
        int access = compile.access;
        String signature = compile.signature;
        List<String> exceptions = compile.exceptions;
        compile.name = ORIGINAL;
        compile.access |= Opcodes.ACC_SYNTHETIC;
        node.methods.add(wrapper(node.name, access, signature, exceptions));
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode wrapper(String owner, int access, String signature,
                                      List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, COMPILE,
            DESCRIPTOR, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(Ljava/lang/Object;)V", false);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, ORIGINAL,
            DESCRIPTOR, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "finish",
            "(Ljava/lang/Object;)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 2);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "cancel",
            "(Ljava/lang/Object;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
