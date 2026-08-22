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

/** Captures ModelRenderer compilation and replaces only its call-list emitters. */
final class ModelRendererVboAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/model/ModelRenderer";
    static final String COMPILE = "func_78788_d";
    static final String ORIGINAL = "rlcraftIce$compileDisplayListOriginal";
    static final String COMPILE_DESCRIPTOR = "(F)V";
    static final String GL_STATE_MANAGER =
        "net/minecraft/client/renderer/GlStateManager";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelMeshCaptureBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("ModelRenderer target changed: " + node.name);
        }
        MethodNode compile = null;
        int matches = 0;
        int callLists = 0;
        for (MethodNode method : node.methods) {
            if (COMPILE.equals(method.name) && COMPILE_DESCRIPTOR.equals(method.desc)) {
                compile = method;
                matches++;
            }
            if (ORIGINAL.equals(method.name)) {
                throw new IllegalStateException("ModelRenderer already adapted");
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && GL_STATE_MANAGER.equals(call.owner)
                    && "func_179148_o".equals(call.name) && "(I)V".equals(call.desc)) {
                    call.owner = BRIDGE;
                    call.name = "callList";
                    callLists++;
                }
            }
        }
        if (matches != 1 || compile == null || callLists != 4
            || (compile.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("ModelRenderer compile/emitter graph changed: compile="
                + matches + ", callLists=" + callLists);
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
            COMPILE_DESCRIPTOR, signature, exceptions == null ? null
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
            COMPILE_DESCRIPTOR, false);
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
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
