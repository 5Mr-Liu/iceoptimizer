package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reliable vanilla/FoamFix texture-upload hook loaded after early CoreMods. */
final class VanillaTextureUploadAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/renderer/texture/TextureUtil";
    static final String TARGET_DESCRIPTOR = "(I[IIIIIZZZ)V";
    static final String ORIGINAL_UPLOAD = "ice$originalUploadTextureSub";
    static final String BRIDGE = "dev/rlcraft/ice/hooks/TextureUploadBootstrap";
    static final String BRIDGE_DESCRIPTOR = "(I[IIIIIZZZ)Z";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("纹理上传目标变化：" + node.name);
        }
        MethodNode upload = requireUniqueDescriptor(node, TARGET_DESCRIPTOR);
        if ((upload.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("TextureUtil 单级上传方法不再是 static");
        }
        if (countTextureUploads(upload) != 1) {
            throw new IllegalStateException("TextureUtil 单级上传调用图变化");
        }
        rejectMethod(node, ORIGINAL_UPLOAD, TARGET_DESCRIPTOR);
        wrap(node, upload);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void wrap(ClassNode node, MethodNode original) {
        int access = original.access;
        String name = original.name;
        String signature = original.signature;
        String[] exceptions = exceptions(original);
        original.name = ORIGINAL_UPLOAD;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, name,
            TARGET_DESCRIPTOR, signature, exceptions);
        LabelNode fallback = new LabelNode();
        loadArguments(wrapper);
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "tryUploadLevel", BRIDGE_DESCRIPTOR, false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(fallback);
        loadArguments(wrapper);
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name,
            ORIGINAL_UPLOAD, TARGET_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);
    }

    private static void loadArguments(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        for (int slot = 2; slot <= 8; slot++) {
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, slot));
        }
    }

    private static int countTextureUploads(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("net/minecraft/client/renderer/GlStateManager".equals(call.owner)
                && "(IIIIIIIILjava/nio/IntBuffer;)V".equals(call.desc)) count++;
        }
        return count;
    }

    private static MethodNode requireUniqueDescriptor(ClassNode node, String descriptor) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (descriptor.equals(method.desc)) {
                found = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(node.name
            + " 单级纹理上传方法数量变化：" + count);
        return found;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 ICE 方法 " + name + descriptor);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String[] exceptions(MethodNode method) {
        List<String> values = method.exceptions;
        return values == null ? null : values.toArray(new String[values.size()]);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
