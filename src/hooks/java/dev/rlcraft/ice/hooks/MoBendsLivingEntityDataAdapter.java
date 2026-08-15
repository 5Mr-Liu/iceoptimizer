package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Moves pure climbing guards before the reviewed three block-state reads. */
final class MoBendsLivingEntityDataAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "goblinbob/mobends/core/data/LivingEntityData";
    static final String ENTITY_DATA = "goblinbob/mobends/core/data/EntityData";
    static final String METHOD = "calcClimbing";
    static final String ORIGINAL = "rlcraftIce$calcClimbingOriginal";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/mobends/MoBendsRenderBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("MoBends LivingEntityData 类名变化：" + node.name);
        MethodNode original = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (METHOD.equals(method.name) && "()Z".equals(method.desc)) {
                original = method;
                matches++;
            }
            if (ORIGINAL.equals(method.name)) throw new IllegalStateException("MoBends LivingEntityData 已存在 ICE 方法");
        }
        if (matches != 1 || original == null) {
            throw new IllegalStateException("MoBends calcClimbing 调用图变化：" + matches);
        }
        int access = original.access;
        original.name = ORIGINAL;
        original.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, METHOD, "()Z",
            original.signature, original.exceptions == null ? null
                : original.exceptions.toArray(new String[original.exceptions.size()]));
        MethodVisitor code = wrapper;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useEntityAnimation", "()Z", false);
        Label fast = new Label();
        code.visitJumpInsn(Opcodes.IFNE, fast);
        callOriginal(code);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(fast);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, ENTITY_DATA, "entity", "Lnet/minecraft/entity/Entity;");
        Label entityPresent = new Label();
        code.visitJumpInsn(Opcodes.IFNONNULL, entityPresent);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(entityPresent);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, ENTITY_DATA, "entity", "Lnet/minecraft/entity/Entity;");
        code.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/entity/EntityLivingBase");
        code.visitVarInsn(Opcodes.ASTORE, 1);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/entity/EntityLivingBase",
            "field_70170_p", "Lnet/minecraft/world/World;");
        Label worldPresent = new Label();
        code.visitJumpInsn(Opcodes.IFNONNULL, worldPresent);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(worldPresent);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/entity/EntityLivingBase",
            "func_70617_f_", "()Z", false);
        Label onLadder = new Label();
        code.visitJumpInsn(Opcodes.IFNE, onLadder);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(onLadder);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "isOnGround", "()Z", false);
        Label airborne = new Label();
        code.visitJumpInsn(Opcodes.IFEQ, airborne);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(airborne);
        callOriginal(code);
        code.visitInsn(Opcodes.IRETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(wrapper);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void callOriginal(MethodVisitor code) {
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, ORIGINAL, "()Z", false);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
