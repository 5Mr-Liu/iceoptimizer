package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact iChunUtil 7.2.x recursive world-render compatibility wrapper. */
final class WorldPortalAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "me/ichun/mods/ichunutil/common/module/worldportals/client/render/WorldPortalRenderer";
    static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/WorldPortalBootstrap";
    static final String METHOD = "renderWorldPortal";
    static final String ORIGINAL = "ice$renderWorldPortalOriginal";
    static final String DESCRIPTOR =
        "(Lnet/minecraft/client/Minecraft;"
        + "Lme/ichun/mods/ichunutil/common/module/worldportals/common/portal/WorldPortal;"
        + "Lnet/minecraft/entity/Entity;[F[FF)V";

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("WorldPortal target changed: " + node.name);
        }
        requireRenderLevel(node);
        if (find(node, ORIGINAL, DESCRIPTOR) != null) {
            throw new IllegalStateException("WorldPortal renderer already adapted");
        }
        MethodNode render = find(node, METHOD, DESCRIPTOR);
        if (render == null || (render.access & Opcodes.ACC_STATIC) == 0
            || (render.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("WorldPortal entry shape changed");
        }
        verifyGraph(render);
        int access = render.access;
        String signature = render.signature;
        List<String> exceptions = render.exceptions;
        render.name = ORIGINAL;
        render.access = privateSynthetic(render.access);
        node.methods.add(wrapper(access, signature, exceptions));
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void requireRenderLevel(ClassNode node) {
        for (FieldNode field : node.fields) {
            if ("renderLevel".equals(field.name) && "I".equals(field.desc)
                && (field.access & Opcodes.ACC_STATIC) != 0) return;
        }
        throw new IllegalStateException("WorldPortal renderLevel ABI changed");
    }

    private static void verifyGraph(MethodNode render) {
        int levelWrites = 0;
        int drawWorld = 0;
        int begins = 0;
        int ends = 0;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.PUTSTATIC
                    && TARGET.equals(field.owner)
                    && "renderLevel".equals(field.name)
                    && "I".equals(field.desc)) levelWrites++;
                continue;
            }
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                && TARGET.equals(call.owner) && "drawWorld".equals(call.name)
                && DESCRIPTOR.equals(call.desc)) drawWorld++;
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                && "org/lwjgl/opengl/GL11".equals(call.owner)) {
                if ("glBegin".equals(call.name) && "(I)V".equals(call.desc)) begins++;
                if ("glEnd".equals(call.name) && "()V".equals(call.desc)) ends++;
            }
        }
        if (levelWrites != 2 || drawWorld != 1 || begins != 1 || ends != 1) {
            throw new IllegalStateException("WorldPortal render graph changed: level="
                + levelWrites + ", world=" + drawWorld + ", immediate="
                + begins + '/' + ends);
        }
    }

    private static MethodNode wrapper(int access, String signature,
                                      List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, METHOD,
            DESCRIPTOR, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "begin", "()J", false);
        Type[] arguments = Type.getArgumentTypes(DESCRIPTOR);
        int argumentSlots = 0;
        for (Type argument : arguments) argumentSlots += argument.getSize();
        int tokenLocal = argumentSlots;
        int errorLocal = tokenLocal + 2;
        code.visitVarInsn(Opcodes.LSTORE, tokenLocal);
        Label start = new Label();
        Label finish = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        int local = 0;
        for (Type argument : arguments) {
            code.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        code.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, ORIGINAL,
            DESCRIPTOR, false);
        code.visitLabel(finish);
        code.visitVarInsn(Opcodes.LLOAD, tokenLocal);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "end", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, errorLocal);
        code.visitVarInsn(Opcodes.LLOAD, tokenLocal);
        code.visitVarInsn(Opcodes.ALOAD, errorLocal);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "abort",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, errorLocal);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, finish, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static MethodNode find(ClassNode node, String name,
                                   String descriptor) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate WorldPortal method " + name);
            found = method;
        }
        return found;
    }

    private static int privateSynthetic(int access) {
        return (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
            | Opcodes.ACC_SYNCHRONIZED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
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
