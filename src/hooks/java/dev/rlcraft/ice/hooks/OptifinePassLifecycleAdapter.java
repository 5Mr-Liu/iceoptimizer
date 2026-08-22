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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact OptiFine G5 shadow/deferred/composite/final pass observer. */
final class OptifinePassLifecycleAdapter implements OptimizerBytecodeAdapter {
    static final String SHADERS = "net/optifine/shaders/Shaders";
    static final String SHADERS_RENDER = "net/optifine/shaders/ShadersRender";
    static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/OptifinePassBootstrap";
    static final String DEFERRED_ORIGINAL = "ice$renderDeferredOriginal";
    static final String COMPOSITE_ORIGINAL = "ice$renderCompositeFinalOriginal";
    static final String SHADOW_ORIGINAL = "ice$renderShadowMapOriginal";
    private static final String COMPOSITES_DESC =
        "([Lnet/optifine/shaders/Program;Z)V";

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (SHADERS.equals(node.name)) transformShaders(node);
        else if (SHADERS_RENDER.equals(node.name)) transformShadersRender(node);
        else throw new IllegalStateException("OptiFine pass target changed");
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformShaders(ClassNode node) {
        if (find(node, DEFERRED_ORIGINAL, "()V") != null
            || find(node, COMPOSITE_ORIGINAL, "()V") != null) {
            throw new IllegalStateException("OptiFine post passes already adapted");
        }
        MethodNode deferred = require(node, "renderDeferred", "()V");
        MethodNode composite = require(node, "renderCompositeFinal", "()V");
        MethodNode composites = require(node, "renderComposites", COMPOSITES_DESC);
        requireStaticConcrete(deferred, "renderDeferred");
        requireStaticConcrete(composite, "renderCompositeFinal");
        requireStaticConcrete(composites, "renderComposites");
        requireCallCount(deferred, SHADERS, "renderComposites", COMPOSITES_DESC, 1);
        requireCallCount(composite, SHADERS, "renderComposites", COMPOSITES_DESC, 1);
        MethodInsnNode renderFinal = uniqueCall(composites, SHADERS,
            "renderFinal", "()V");
        InsnList transition = new InsnList();
        transition.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "transitionFinal", "()V", false));
        composites.instructions.insertBefore(renderFinal, transition);
        wrapStaticNoArgs(node, deferred, DEFERRED_ORIGINAL,
            "beginDeferred", "endDeferred", "abortDeferred");
        wrapStaticNoArgs(node, composite, COMPOSITE_ORIGINAL,
            "beginComposite", "endComposite", "abortComposite");
    }

    private static void transformShadersRender(ClassNode node) {
        if (findByName(node, SHADOW_ORIGINAL) != null) {
            throw new IllegalStateException("OptiFine shadow pass already adapted");
        }
        MethodNode shadow = null;
        for (MethodNode method : node.methods) {
            if (!"renderShadowMap".equals(method.name)
                || !isShadowDescriptor(method.desc)) continue;
            if (shadow != null) throw new IllegalStateException(
                "duplicate OptiFine renderShadowMap");
            shadow = method;
        }
        if (shadow == null) throw new IllegalStateException(
            "missing OptiFine renderShadowMap");
        requireStaticConcrete(shadow, "renderShadowMap");
        int shadowWrites = 0;
        int cameraCalls = 0;
        for (AbstractInsnNode instruction : shadow.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode
                && instruction.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (SHADERS.equals(field.owner)
                    && "isShadowPass".equals(field.name)
                    && "Z".equals(field.desc)) shadowWrites++;
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (SHADERS.equals(call.owner)
                    && "setCameraShadow".equals(call.name)) cameraCalls++;
            }
        }
        if (shadowWrites != 2 || cameraCalls != 1) {
            throw new IllegalStateException("OptiFine shadow graph changed: "
                + shadowWrites + '/' + cameraCalls);
        }
        int access = shadow.access;
        String descriptor = shadow.desc;
        String signature = shadow.signature;
        List<String> exceptions = shadow.exceptions;
        shadow.name = SHADOW_ORIGINAL;
        shadow.access = privateSynthetic(shadow.access);
        node.methods.add(staticWrapper(access, "renderShadowMap", descriptor,
            signature, exceptions, SHADOW_ORIGINAL, "beginShadow",
            "endShadow", "abortShadow"));
    }

    private static void wrapStaticNoArgs(ClassNode node, MethodNode original,
                                         String originalName, String begin,
                                         String end, String abort) {
        int access = original.access;
        String signature = original.signature;
        List<String> exceptions = original.exceptions;
        original.name = originalName;
        original.access = privateSynthetic(original.access);
        node.methods.add(staticWrapper(access, originalName.substring(4,
            originalName.length() - "Original".length()), "()V", signature,
            exceptions, originalName, begin, end, abort));
    }

    private static MethodNode staticWrapper(int access, String publicName,
                                            String descriptor, String signature,
                                            List<String> exceptions,
                                            String originalName, String begin,
                                            String end, String abort) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, publicName,
            descriptor, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, begin, "()J", false);
        Type[] arguments = Type.getArgumentTypes(descriptor);
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
        code.visitMethodInsn(Opcodes.INVOKESTATIC,
            SHADOW_ORIGINAL.equals(originalName) ? SHADERS_RENDER : SHADERS,
            originalName, descriptor, false);
        code.visitLabel(finish);
        code.visitVarInsn(Opcodes.LLOAD, tokenLocal);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, end, "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, errorLocal);
        code.visitVarInsn(Opcodes.LLOAD, tokenLocal);
        code.visitVarInsn(Opcodes.ALOAD, errorLocal);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, abort,
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, errorLocal);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, finish, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static boolean isShadowDescriptor(String descriptor) {
        if (Type.getReturnType(descriptor).getSort() != Type.VOID) return false;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        return arguments.length == 4 && arguments[0].getSort() == Type.OBJECT
            && arguments[1].getSort() == Type.INT
            && arguments[2].getSort() == Type.FLOAT
            && arguments[3].getSort() == Type.LONG;
    }

    private static void requireCallCount(MethodNode method, String owner,
                                         String name, String descriptor,
                                         int expected) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) count++;
        }
        if (count != expected) throw new IllegalStateException(
            "OptiFine post graph changed: " + name + '=' + count);
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner,
                                             String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!owner.equals(call.owner) || !name.equals(call.name)
                || !descriptor.equals(call.desc)) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate OptiFine call " + name + descriptor);
            found = call;
        }
        if (found == null) throw new IllegalStateException(
            "missing OptiFine call " + name + descriptor);
        return found;
    }

    private static void requireStaticConcrete(MethodNode method, String detail) {
        if ((method.access & Opcodes.ACC_STATIC) == 0
            || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("OptiFine " + detail
                + " shape changed");
        }
    }

    private static int privateSynthetic(int access) {
        return (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED
            | Opcodes.ACC_SYNCHRONIZED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    }

    private static MethodNode require(ClassNode node, String name,
                                      String descriptor) {
        MethodNode method = find(node, name, descriptor);
        if (method == null) throw new IllegalStateException(
            "missing " + name + descriptor);
        return method;
    }

    private static MethodNode find(ClassNode node, String name,
                                   String descriptor) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate " + name + descriptor);
            found = method;
        }
        return found;
    }

    private static MethodNode findByName(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return method;
        return null;
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
