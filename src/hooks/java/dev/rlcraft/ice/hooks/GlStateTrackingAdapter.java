package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Publishes wrapper-confirmed GL state to the standalone Core-JAR mirror. */
final class GlStateTrackingAdapter implements OptimizerBytecodeAdapter {
    enum Part { OPENGL_HELPER, GL_STATE_MANAGER }

    static final String OPENGL_HELPER = "net/minecraft/client/renderer/OpenGlHelper";
    static final String GL_STATE_MANAGER = "net/minecraft/client/renderer/GlStateManager";
    static final String TRACKER =
        "dev/rlcraft/ice/optimizer/compat/gl/EarlyGlStateTracker";
    static final String MATRIX_TRACKER =
        "dev/rlcraft/ice/optimizer/compat/gl/EarlyMatrixStateTracker";
    static final String HUD_BOOTSTRAP =
        "dev/rlcraft/ice/hooks/HudRenderBootstrap";
    private final Part part;

    GlStateTrackingAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (part == Part.OPENGL_HELPER) transformOpenGlHelper(node);
        else transformGlStateManager(node);
        ClassWriter writer = new ClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformOpenGlHelper(ClassNode node) {
        requireClass(node, OPENGL_HELPER);
        injectWithBarrier(node, "func_153161_d", "(I)V", "useProgram", "(I)V", 1);
        injectWithBarrier(node, "func_153171_g", "(II)V", "bindFramebuffer", "(II)V", 2);
        injectWithBarrier(node, "func_176072_g", "(II)V", "bindBuffer", "(II)V", 2);
        injectWithBarrier(node, "func_77472_b", "(I)V", "clientActiveTexture", "(I)V", 1);
        injectWithBarrier(node, "func_148821_a", "(IIII)V", "blendFunction",
            "(IIII)V", 4);
    }

    private static void transformGlStateManager(ClassNode node) {
        requireClass(node, GL_STATE_MANAGER);
        injectWithBarrier(node, "func_179143_c", "(I)V", "depthFunction", "(I)V", 1);
        injectWithBarrier(node, "func_179138_g", "(I)V", "activeTexture", "(I)V", 1);
        injectTextureBinding(node, "func_179144_i", "(I)V");
        injectBooleanWithBarrier(node, "func_179147_l", true, "blendEnabled");
        injectBooleanWithBarrier(node, "func_179084_k", false, "blendEnabled");
        injectBooleanWithBarrier(node, "func_179098_w", true, "texture2dEnabled");
        injectBooleanWithBarrier(node, "func_179090_x", false, "texture2dEnabled");
        injectWithBarrier(node, "func_179112_b", "(II)V", "blendFunction", "(II)V", 2);
        injectWithBarrier(node, "func_179120_a", "(IIII)V", "blendFunction", "(IIII)V", 4);
        injectWithBarrier(node, "func_187398_d", "(I)V", "blendEquation", "(I)V", 1);
        injectBooleanWithBarrier(node, "func_179126_j", true, "depthEnabled");
        injectBooleanWithBarrier(node, "func_179097_i", false, "depthEnabled");
        injectWithBarrier(node, "func_179132_a", "(Z)V", "depthMask", "(Z)V", 1);
        injectBooleanWithBarrier(node, "func_179089_o", true, "cullEnabled");
        injectBooleanWithBarrier(node, "func_179129_p", false, "cullEnabled");
        injectBooleanWithBarrier(node, "func_179145_e", true, "lightingEnabled");
        injectBooleanWithBarrier(node, "func_179140_f", false, "lightingEnabled");
        injectWithBarrier(node, "func_179107_e", "(I)V", "cullFace", "(I)V", 1);
        injectWithBarrier(node, "func_179135_a", "(ZZZZ)V", "colorMask", "(ZZZZ)V", 4);
        injectWithBarrier(node, "func_179083_b", "(IIII)V", "viewport",
            "(IIII)V", 4);
        injectTypedWithBarrier(node, "func_179131_c", "(FFFF)V", TRACKER,
            "color", "(FFFF)V");
        injectTypedWithBarrier(node, "func_179124_c", "(FFF)V", TRACKER,
            "color", "(FFF)V");
        injectTypedWithBarrier(node, "func_179117_G", "()V", TRACKER,
            "resetColor", "()V");
        injectTypedWithBarrier(node, "func_179128_n", "(I)V", MATRIX_TRACKER,
            "matrixMode", "(I)V");
        injectTypedWithBarrier(node, "func_179096_D", "()V", MATRIX_TRACKER,
            "loadIdentity", "()V");
        injectTypedWithBarrier(node, "func_179094_E", "()V", MATRIX_TRACKER,
            "pushMatrix", "()V");
        injectTypedWithBarrier(node, "func_179121_F", "()V", MATRIX_TRACKER,
            "popMatrix", "()V");
        injectTypedWithBarrier(node, "func_179114_b", "(FFFF)V", MATRIX_TRACKER,
            "rotate", "(FFFF)V");
        injectTypedWithBarrier(node, "func_179152_a", "(FFF)V", MATRIX_TRACKER,
            "scale", "(FFF)V");
        injectTypedWithBarrier(node, "func_179139_a", "(DDD)V", MATRIX_TRACKER,
            "scale", "(DDD)V");
        injectTypedWithBarrier(node, "func_179109_b", "(FFF)V", MATRIX_TRACKER,
            "translate", "(FFF)V");
        injectTypedWithBarrier(node, "func_179137_b", "(DDD)V", MATRIX_TRACKER,
            "translate", "(DDD)V");
        injectTypedWithBarrier(node, "func_179110_a", "(Ljava/nio/FloatBuffer;)V",
            MATRIX_TRACKER, "multMatrix", "(Ljava/nio/FloatBuffer;)V");
        injectTypedWithBarrier(node, "func_179130_a", "(DDDDDD)V",
            MATRIX_TRACKER, "ortho", "(DDDDDD)V");
    }

    private static void injectTypedWithBarrier(ClassNode node, String methodName,
                                               String descriptor, String owner,
                                               String trackerMethod,
                                               String trackerDescriptor) {
        MethodNode method = requireMethod(node, methodName, descriptor);
        injectHudBarrier(method);
        injectTyped(node, methodName, descriptor, owner, trackerMethod,
            trackerDescriptor);
    }

    private static void injectBooleanWithBarrier(ClassNode node, String methodName,
                                                 boolean value,
                                                 String trackerMethod) {
        MethodNode method = requireMethod(node, methodName, "()V");
        injectHudBarrier(method);
        injectBoolean(node, methodName, value, trackerMethod);
    }

    private static void injectWithBarrier(ClassNode node, String methodName,
                                          String descriptor,
                                          String trackerMethod,
                                          String trackerDescriptor,
                                          int argumentCount) {
        MethodNode method = requireMethod(node, methodName, descriptor);
        injectHudBarrier(method);
        inject(node, methodName, descriptor, trackerMethod, trackerDescriptor,
            argumentCount);
    }

    private static void injectTextureBinding(ClassNode node, String methodName,
                                             String descriptor) {
        MethodNode method = requireMethod(node, methodName, descriptor);
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ILOAD, 0));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HUD_BOOTSTRAP,
            "textureBarrier", "(I)V", false));
        method.instructions.insert(before);
        inject(node, methodName, descriptor, "bindTexture", "(I)V", 1);
    }

    private static void injectHudBarrier(MethodNode method) {
        InsnList before = new InsnList();
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HUD_BOOTSTRAP,
            "barrier", "()V", false));
        method.instructions.insert(before);
    }

    private static void injectTyped(ClassNode node, String methodName,
                                    String descriptor, String owner,
                                    String trackerMethod,
                                    String trackerDescriptor) {
        MethodNode method = requireMethod(node, methodName, descriptor);
        if ((method.access & Opcodes.ACC_STATIC) == 0
            || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(methodName + " is not a concrete static wrapper");
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList publish = new InsnList();
            int local = 0;
            for (Type argument : arguments) {
                publish.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                local += argument.getSize();
            }
            publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner,
                trackerMethod, trackerDescriptor, false));
            method.instructions.insertBefore(instruction, publish);
            returns++;
        }
        if (returns == 0) throw new IllegalStateException(methodName + " has no return");
    }

    private static void injectBoolean(ClassNode node, String methodName,
                                      boolean value, String trackerMethod) {
        MethodNode method = requireMethod(node, methodName, "()V");
        if ((method.access & Opcodes.ACC_STATIC) == 0
            || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(methodName + " is not a concrete static wrapper");
        }
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList publish = new InsnList();
            publish.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRACKER,
                trackerMethod, "(Z)V", false));
            method.instructions.insertBefore(instruction, publish);
            returns++;
        }
        if (returns == 0) throw new IllegalStateException(methodName + " has no return");
    }

    private static void inject(ClassNode node, String methodName, String descriptor,
                               String trackerMethod, String trackerDescriptor,
                               int argumentCount) {
        MethodNode method = requireMethod(node, methodName, descriptor);
        if ((method.access & Opcodes.ACC_STATIC) == 0
            || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(methodName + " is not a concrete static wrapper");
        }
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList publish = new InsnList();
            for (int local = 0; local < argumentCount; local++) {
                publish.add(new VarInsnNode(Opcodes.ILOAD, local));
            }
            publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRACKER,
                trackerMethod, trackerDescriptor, false));
            method.instructions.insertBefore(instruction, publish);
            returns++;
        }
        if (returns == 0) throw new IllegalStateException(methodName + " has no return");
    }

    private static void requireClass(ClassNode node, String expected) {
        if (!expected.equals(node.name)) throw new IllegalStateException("target " + node.name);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        throw new IllegalStateException("missing method " + name + descriptor);
    }
}
