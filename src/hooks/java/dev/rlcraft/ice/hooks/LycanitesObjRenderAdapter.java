package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Wraps one reviewed Lycanites OBJ/VBO group implementation in an exact cache gate. */
final class LycanitesObjRenderAdapter implements OptimizerBytecodeAdapter {
    static final String METHOD = "renderGroupImpl";
    static final String ORIGINAL = "rlcraftIce$renderGroupImplOriginal";
    static final String DESCRIPTOR =
        "(Lcom/lycanitesmobs/client/obj/ObjObject;Ljavax/vecmath/Vector4f;"
            + "Ljavax/vecmath/Vector2f;Lnet/minecraft/client/renderer/vertex/VertexFormat;)V";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesObjRenderBridge";
    static final String BRIDGE_DESCRIPTOR =
        "(Ljava/lang/Object;Ljava/lang/Object;Ljavax/vecmath/Vector4f;Ljavax/vecmath/Vector2f;"
            + "Lnet/minecraft/client/renderer/vertex/VertexFormat;)Z";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        // This adapter only renames the reviewed implementation and adds one wrapper. Preserve
        // every existing StackMapTable exactly: post-mixin Lycanites classes contain branch
        // merges whose common type is Forge Event, which cannot safely be approximated as Object.
        reader.accept(node, 0);
        String expected = transformedName.replace('.', '/');
        if (!expected.equals(node.name)) throw new IllegalStateException("Lycanites OBJ 类名变化：" + node.name);
        MethodNode implementation = null;
        int methods = 0;
        for (MethodNode method : node.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                implementation = method;
                methods++;
            }
            if (ORIGINAL.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                throw new IllegalStateException("Lycanites OBJ 已存在 ICE 原始方法");
            }
        }
        if (methods != 1 || implementation == null
            || (implementation.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) {
            throw new IllegalStateException("Lycanites OBJ renderGroupImpl 调用图变化：methods=" + methods);
        }
        int wrapperAccess = implementation.access;
        implementation.name = ORIGINAL;
        implementation.access |= Opcodes.ACC_SYNTHETIC;

        MethodVisitor wrapper = new MethodNode(Opcodes.ASM5, wrapperAccess, METHOD, DESCRIPTOR,
            implementation.signature, implementation.exceptions == null ? null
                : implementation.exceptions.toArray(new String[implementation.exceptions.size()]));
        wrapper.visitCode();
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitVarInsn(Opcodes.ALOAD, 1);
        wrapper.visitVarInsn(Opcodes.ALOAD, 2);
        wrapper.visitVarInsn(Opcodes.ALOAD, 3);
        wrapper.visitVarInsn(Opcodes.ALOAD, 4);
        wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "tryRender", BRIDGE_DESCRIPTOR, false);
        Label fallback = new Label();
        wrapper.visitJumpInsn(Opcodes.IFEQ, fallback);
        wrapper.visitInsn(Opcodes.RETURN);
        wrapper.visitLabel(fallback);
        wrapper.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitVarInsn(Opcodes.ALOAD, 1);
        wrapper.visitVarInsn(Opcodes.ALOAD, 2);
        wrapper.visitVarInsn(Opcodes.ALOAD, 3);
        wrapper.visitVarInsn(Opcodes.ALOAD, 4);
        wrapper.visitMethodInsn(Opcodes.INVOKESPECIAL, node.name, ORIGINAL, DESCRIPTOR, false);
        wrapper.visitInsn(Opcodes.RETURN);
        wrapper.visitMaxs(0, 0);
        wrapper.visitEnd();
        node.methods.add((MethodNode) wrapper);

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
