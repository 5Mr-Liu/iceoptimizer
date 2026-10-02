package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes repeated parameter writes inside a reviewed sprite mip helper. */
final class VanillaTextureUploadAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "net/minecraft/client/renderer/texture/TextureUtil";
    static final String TARGET_DESCRIPTOR = "(I[IIIIIZZZ)V";
    static final String BRIDGE = "dev/rlcraft/ice/hooks/TextureUploadBootstrap";
    static final String BRIDGE_DESCRIPTOR = "(IZZZ)Z";
    private static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw changed("class " + node.name);
        MethodNode upload = requireUniqueDescriptor(node, TARGET_DESCRIPTOR);
        if ((upload.access & Opcodes.ACC_STATIC) == 0) throw changed("non-static upload");
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (BRIDGE.equals(call.owner) && "skipParameters".equals(call.name)
                    && BRIDGE_DESCRIPTOR.equals(call.desc)) return originalClass;
            }
        }
        MethodInsnNode blur = null;
        MethodInsnNode clamp = null;
        int uploads = 0;
        int copies = 0;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (TARGET.equals(call.owner) && "(ZZ)V".equals(call.desc)) {
                if (blur != null || clamp != null) throw changed("filter setup order");
                blur = call;
                requireParameterHelper(node, call);
            } else if (TARGET.equals(call.owner) && "(Z)V".equals(call.desc)) {
                if (clamp != null || blur == null) throw changed("wrap setup order");
                clamp = call;
                requireParameterHelper(node, call);
            } else if (TARGET.equals(call.owner) && "([III)V".equals(call.desc)) {
                requireCopyGraph(node, call);
                copies++;
            } else if (GL_STATE.equals(call.owner)
                && "(IIIIIIIILjava/nio/IntBuffer;)V".equals(call.desc)) uploads++;
            else if (!"java/lang/Math".equals(call.owner)
                || !"min".equals(call.name) || !"(II)I".equals(call.desc)) {
                throw changed("unknown upload callback " + call.owner + "." + call.name);
            }
        }
        if (blur == null || clamp == null || uploads != 1 || copies != 1) {
            throw changed("setup/upload count");
        }
        AbstractInsnNode filterMip = previousCode(blur);
        AbstractInsnNode filterBlur = previousCode(filterMip);
        AbstractInsnNode wrap = previousCode(clamp);
        requireLoad(filterBlur, 6);
        requireLoad(filterMip, 8);
        requireLoad(wrap, 7);
        if (previousCode(wrap) != blur) throw changed("setup span");
        LabelNode afterSetup = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ILOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 6));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 7));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 8));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "skipParameters", BRIDGE_DESCRIPTOR, false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, afterSetup));
        upload.instructions.insertBefore(filterBlur, guard);
        upload.instructions.insert(clamp, afterSetup);
        ClassWriter writer = new ClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void requireParameterHelper(ClassNode node, MethodInsnNode call) {
        MethodNode helper = require(node, call.name, call.desc);
        int writes = 0;
        for (AbstractInsnNode instruction : helper.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode nested = (MethodInsnNode) instruction;
            // Reviewed OptiFine G5 reads ofMipmapType/isMultiTexture here.
            // They read settings only; no bind, callback, or GL write occurs
            // between consecutive levels of the FoamFix helper.
            if ("Config".equals(nested.owner) && "getMipmapType".equals(nested.name)
                && "()I".equals(nested.desc) && "(ZZ)V".equals(call.desc)) continue;
            if (!GL_STATE.equals(nested.owner) || !"(III)V".equals(nested.desc)
                || !("func_187421_b".equals(nested.name) || "glTexParameteri".equals(nested.name))
                || nested.getOpcode() != Opcodes.INVOKESTATIC) {
                throw changed("parameter helper callback");
            }
            writes++;
        }
        // Each boolean branch writes both parameters.
        if (writes != 4) throw changed("parameter helper writes " + writes);
    }

    private static void requireCopyGraph(ClassNode node, MethodInsnNode copy) {
        MethodNode helper = require(node, copy.name, copy.desc);
        for (AbstractInsnNode instruction : helper.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (TARGET.equals(call.owner) && "([I)[I".equals(call.desc)) {
                requireAnaglyphGraph(node, require(node, call.name, call.desc));
            } else if ("net/minecraft/client/Minecraft".equals(call.owner)
                && "()Lnet/minecraft/client/Minecraft;".equals(call.desc)) { }
            else if (!call.owner.startsWith("java/nio/")) {
                throw changed("copy callback " + call.owner + "." + call.name);
            }
        }
    }

    private static void requireAnaglyphGraph(ClassNode node, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!TARGET.equals(call.owner) || !"(I)I".equals(call.desc)) {
                throw changed("anaglyph callback");
            }
            MethodNode color = require(node, call.name, call.desc);
            for (AbstractInsnNode colorInstruction : color.instructions.toArray()) {
                if (colorInstruction instanceof MethodInsnNode) throw changed("color callback");
            }
        }
    }

    private static MethodNode requireUniqueDescriptor(ClassNode node, String descriptor) {
        MethodNode found = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (descriptor.equals(method.desc)) { found = method; count++; }
        }
        if (count != 1) throw changed("upload descriptor count " + count);
        return found;
    }

    private static MethodNode require(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        throw changed("missing " + name + descriptor);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static void requireLoad(AbstractInsnNode instruction, int slot) {
        if (!(instruction instanceof VarInsnNode) || instruction.getOpcode() != Opcodes.ILOAD
            || ((VarInsnNode) instruction).var != slot) throw changed("parameter arguments");
    }

    private static IllegalStateException changed(String detail) {
        return new IllegalStateException("TextureUtil parameter upload graph changed: " + detail);
    }
}
