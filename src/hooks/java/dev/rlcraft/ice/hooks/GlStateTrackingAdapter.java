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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.JumpInsnNode;

/** Publishes wrapper-confirmed GL state to the standalone Core-JAR mirror. */
final class GlStateTrackingAdapter implements OptimizerBytecodeAdapter {
    enum Part { OPENGL_HELPER, GL_STATE_MANAGER, BOOLEAN_STATE }

    static final String OPENGL_HELPER = "net/minecraft/client/renderer/OpenGlHelper";
    static final String GL_STATE_MANAGER = "net/minecraft/client/renderer/GlStateManager";
    static final String BOOLEAN_STATE = GL_STATE_MANAGER + "$BooleanState";
    static final String TRACKER =
        "dev/rlcraft/ice/optimizer/compat/gl/EarlyGlStateTracker";
    static final String MATRIX_TRACKER =
        "dev/rlcraft/ice/optimizer/compat/gl/EarlyMatrixStateTracker";
    static final String HUD_BOOTSTRAP =
        "dev/rlcraft/ice/hooks/HudRenderBootstrap";
    static final String MODEL_BARRIER =
        "dev/rlcraft/ice/optimizer/compat/gl/EarlyModelBatchBarrier";
    static final String MODEL_LIGHTING =
        "dev/rlcraft/ice/optimizer/compat/gl/EarlyModelLightingState";
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
        else if (part == Part.BOOLEAN_STATE) transformBooleanState(node);
        else transformGlStateManager(node);
        if (part != Part.BOOLEAN_STATE) {
            injectModelMutationBarriers(node);
            injectModelLightingWrappers(node);
            injectDeferredTransforms(node);
        }
        ClassWriter writer = new ClassWriter(reader,
            ClassWriter.COMPUTE_MAXS
                | (part == Part.BOOLEAN_STATE ? 0 : ClassWriter.COMPUTE_FRAMES));
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Includes light/material/fog wrappers that do not have a state-mirror field. */
    private static void injectModelMutationBarriers(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ((GL_STATE_MANAGER + "$BooleanState").equals(call.owner)
                    && !"<init>".equals(call.name)) {
                    method.instructions.insertBefore(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, MODEL_BARRIER, "barrier", "()V", false));
                }
                if (call.owner.startsWith("org/lwjgl/opengl/")
                    && ("glGetFloat".equals(call.name) || "glGetDouble".equals(call.name))) {
                    method.instructions.insertBefore(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, MODEL_BARRIER, "barrier", "()V", false));
                }
                if (!call.owner.startsWith("org/lwjgl/opengl/")
                    || !call.name.startsWith("gl") || call.name.startsWith("glGet")
                    || call.name.startsWith("glIs") || call.name.startsWith("glCheck")) continue;
                boolean matrix = "glMatrixMode".equals(call.name)
                    || "glPushMatrix".equals(call.name) || "glPopMatrix".equals(call.name)
                    || "glLoadIdentity".equals(call.name) || call.name.startsWith("glLoadMatrix")
                    || call.name.startsWith("glMultMatrix")
                    || call.name.startsWith("glTranslate") || call.name.startsWith("glRotate")
                    || call.name.startsWith("glScale") || call.name.startsWith("glOrtho")
                    || call.name.startsWith("glFrustum");
                boolean nativeArithmetic = "glLoadIdentity".equals(call.name)
                    || call.name.startsWith("glLoadMatrix") || call.name.startsWith("glMultMatrix")
                    || "glOrtho".equals(call.name) || "glFrustum".equals(call.name);
                method.instructions.insertBefore(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, MODEL_BARRIER,
                    matrix && !nativeArithmetic ? "matrixBarrier" : "barrier", "()V", false));
                if ("glFogi".equals(call.name) && "(II)V".equals(call.desc)) {
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP2));
                    method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        TRACKER, "fogi", "(II)V", false));
                }
                if (("glEnable".equals(call.name) || "glDisable".equals(call.name))
                    && "(I)V".equals(call.desc)) {
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
                    InsnList publish = new InsnList();
                    publish.add(new InsnNode("glEnable".equals(call.name) ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                    publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MODEL_LIGHTING, "capability", "(IZ)V", false));
                    method.instructions.insert(call, publish);
                } else if ("glColorMaterial".equals(call.name) && "(II)V".equals(call.desc)) {
                    copyNativeArguments(method, call, "colorMaterial", "(II)V", Opcodes.DUP2);
                } else if ("glLightModeli".equals(call.name) || "glLightModelf".equals(call.name)
                    || "glLightModel".equals(call.name)) {
                    if ("(II)V".equals(call.desc) || "(IF)V".equals(call.desc)
                        || "(ILjava/nio/FloatBuffer;)V".equals(call.desc))
                        copyNativeArguments(method, call, "lightModel", call.desc, Opcodes.DUP2);
                } else if (call.name.startsWith("glActiveTexture") && "(I)V".equals(call.desc)) {
                    copyNativeArguments(method, call, "activeTexture", "(I)V", Opcodes.DUP);
                } else if ("glPopAttrib".equals(call.name)) {
                    method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        MODEL_LIGHTING, "invalidate", "()V", false));
                }
            }
        }
    }

    private static void copyNativeArguments(MethodNode method, MethodInsnNode call,
                                            String target, String descriptor, int duplicate) {
        method.instructions.insertBefore(call, new InsnNode(duplicate));
        method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
            MODEL_LIGHTING, target, descriptor, false));
    }

    /** The helper updates the software matrix once; its early return skips native arithmetic. */
    private static void injectDeferredTransforms(ClassNode node) {
        if (!GL_STATE_MANAGER.equals(node.name)) return;
        for (MethodNode method : node.methods) {
            String target = null;
            if ("func_179109_b".equals(method.name) || "func_179137_b".equals(method.name)) target = "deferTranslate";
            else if ("func_179152_a".equals(method.name) || "func_179139_a".equals(method.name)) target = "deferScale";
            else if ("func_179114_b".equals(method.name)) target = "deferRotate";
            else if ("func_179110_a".equals(method.name)) target = "deferMultMatrix";
            else if ("func_179094_E".equals(method.name)) target = "deferPush";
            else if ("func_179121_F".equals(method.name)) target = "deferPop";
            if (target == null) continue;
            InsnList before = new InsnList();
            int local = 0;
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                before.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                local += argument.getSize();
            }
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MODEL_BARRIER,
                target, method.desc.substring(0, method.desc.length() - 1) + "Z", false));
            LabelNode ordinary = new LabelNode();
            before.add(new JumpInsnNode(Opcodes.IFEQ, ordinary));
            before.add(new InsnNode(Opcodes.RETURN));
            before.add(ordinary);
            method.instructions.insert(before);
        }
    }

    /** BooleanState owns the native enable instruction; publish from its public wrappers. */
    private static void injectModelLightingWrappers(ClassNode node) {
        if (!GL_STATE_MANAGER.equals(node.name)) return;
        for (MethodNode method : node.methods) {
            int capability = 0;
            boolean enabled = false;
            if ("func_179108_z".equals(method.name)) { capability = 2977; enabled = true; }
            else if ("func_179133_A".equals(method.name)) capability = 2977;
            else if ("func_179091_B".equals(method.name)) { capability = 32826; enabled = true; }
            else if ("func_179101_C".equals(method.name)) capability = 32826;
            else if ("func_179142_g".equals(method.name)) { capability = 2903; enabled = true; }
            else if ("func_179119_h".equals(method.name)) capability = 2903;
            boolean light = "func_179085_a".equals(method.name) || "func_179122_b".equals(method.name);
            boolean texgen = "func_179087_a".equals(method.name) || "func_179100_b".equals(method.name);
            if (capability == 0 && !light && !texgen) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.RETURN) continue;
                InsnList publish = new InsnList();
                if (light) {
                    publish.add(new VarInsnNode(Opcodes.ILOAD, 0));
                    enabled = "func_179085_a".equals(method.name);
                } else if (texgen) {
                    publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    publish.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Enum", "ordinal", "()I", false));
                    enabled = "func_179087_a".equals(method.name);
                } else publish.add(new LdcInsnNode(Integer.valueOf(capability)));
                publish.add(new InsnNode(enabled ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                publish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MODEL_LIGHTING,
                    light ? "light" : texgen ? "texGenCoord" : "capability", "(IZ)V", false));
                method.instructions.insertBefore(instruction, publish);
            }
        }
    }

    private static void transformOpenGlHelper(ClassNode node) {
        requireClass(node, OPENGL_HELPER);
        injectWithBarrier(node, "func_153161_d", "(I)V", "useProgram", "(I)V", 1);
        injectWithBarrier(node, "func_153171_g", "(II)V", "bindFramebuffer", "(II)V", 2);
        injectWithBarrier(node, "func_176072_g", "(II)V", "bindBuffer", "(II)V", 2);
        // GlStateManager.setActiveTexture delegates its actual mutation here.
        injectHudMutationBarriers(requireMethod(node, "func_77473_a", "(I)V"));
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
        if (TRACKER.equals(owner)) injectHudMutationBarriers(method);
        else injectHudBarrier(method);
        injectTyped(node, methodName, descriptor, owner, trackerMethod,
            trackerDescriptor);
    }

    private static void injectBooleanWithBarrier(ClassNode node, String methodName,
                                                 boolean value,
                                                 String trackerMethod) {
        MethodNode method = requireMethod(node, methodName, "()V");
        // BooleanState drains the stream only inside its existing native
        // enable/disable branch. Calling an already enabled wrapper is free.
        injectBoolean(node, methodName, value, trackerMethod);
    }

    private static void injectWithBarrier(ClassNode node, String methodName,
                                          String descriptor,
                                          String trackerMethod,
                                          String trackerDescriptor,
                                          int argumentCount) {
        MethodNode method = requireMethod(node, methodName, descriptor);
        injectHudMutationBarriers(method);
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

    private static void transformBooleanState(ClassNode node) {
        requireClass(node, BOOLEAN_STATE);
        for (MethodNode method : node.methods) injectHudMutationBarriers(method);
    }

    /** Keep Minecraft's own no-op branch; do not duplicate its state cache. */
    private static void injectHudMutationBarriers(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            boolean nativeMutation = call.owner.startsWith("org/lwjgl/opengl/")
                && call.name.startsWith("gl") && !call.name.startsWith("glGet")
                && !call.name.startsWith("glIs") && !call.name.startsWith("glCheck");
            boolean shaderUniform = call.owner.startsWith("net/optifine/shaders/uniform/")
                && "setValue".equals(call.name);
            if (!nativeMutation && !shaderUniform) continue;
            // Arguments are already on the stack. A ()V call preserves them,
            // including category-2 arguments, and introduces no new branch.
            method.instructions.insertBefore(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, HUD_BOOTSTRAP, "barrier", "()V", false));
        }
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

