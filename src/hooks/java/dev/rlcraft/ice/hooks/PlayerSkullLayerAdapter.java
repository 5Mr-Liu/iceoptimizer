package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Moves LayerCustomHead's incomplete-profile lookup off the render thread. */
final class PlayerSkullLayerAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET_DESCRIPTOR =
        "(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V";
    static final String PROFILE_DESCRIPTOR =
        "(Lcom/mojang/authlib/GameProfile;)Lcom/mojang/authlib/GameProfile;";
    static final String SKULL_OWNER = "net/minecraft/tileentity/TileEntitySkull";
    static final String RENDERER_OWNER =
        "net/minecraft/client/renderer/tileentity/TileEntitySkullRenderer";
    static final String RENDER_DESCRIPTOR =
        "(FFFLnet/minecraft/util/EnumFacing;FILcom/mojang/authlib/GameProfile;IF)V";
    static final String BRIDGE_OWNER =
        "dev/rlcraft/ice/optimizer/compat/skull/SkullProfileBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("LayerCustomHead 类名变化：" + node.name);
        }

        MethodNode renderLayer = null;
        int methods = 0;
        for (MethodNode method : node.methods) {
            if (("func_177141_a".equals(method.name) || "doRenderLayer".equals(method.name))
                && TARGET_DESCRIPTOR.equals(method.desc)) {
                renderLayer = method;
                methods++;
            }
        }
        if (methods != 1 || renderLayer == null) {
            throw new IllegalStateException("LayerCustomHead 渲染方法匹配数量应为 1，实际 " + methods);
        }

        MethodInsnNode synchronousLookup = null;
        MethodInsnNode renderSkull = null;
        int lookupCalls = 0;
        int renderCalls = 0;
        for (AbstractInsnNode instruction : renderLayer.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKESTATIC && SKULL_OWNER.equals(call.owner)
                && ("func_174884_b".equals(call.name) || "updateGameProfile".equals(call.name))
                && PROFILE_DESCRIPTOR.equals(call.desc)) {
                synchronousLookup = call;
                lookupCalls++;
            }
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && RENDERER_OWNER.equals(call.owner)
                && ("func_188190_a".equals(call.name) || "renderSkull".equals(call.name))
                && RENDER_DESCRIPTOR.equals(call.desc)) {
                renderSkull = call;
                renderCalls++;
            }
        }
        if (lookupCalls != 1 || synchronousLookup == null || renderCalls != 1 || renderSkull == null) {
            throw new IllegalStateException("LayerCustomHead 调用图变化：profileLookup=" + lookupCalls
                + ", renderSkull=" + renderCalls);
        }

        synchronousLookup.owner = BRIDGE_OWNER;
        synchronousLookup.name = "resolveForRenderLookup";
        synchronousLookup.itf = false;

        AbstractInsnNode animateArgument = previousOpcode(renderSkull);
        AbstractInsnNode destroyArgument = previousOpcode(animateArgument);
        AbstractInsnNode profileArgument = previousOpcode(destroyArgument);
        if (!(profileArgument instanceof VarInsnNode) || profileArgument.getOpcode() != Opcodes.ALOAD
            || destroyArgument == null || destroyArgument.getOpcode() != Opcodes.ICONST_M1
            || !(animateArgument instanceof VarInsnNode) || animateArgument.getOpcode() != Opcodes.FLOAD) {
            throw new IllegalStateException("LayerCustomHead renderSkull 参数装载顺序变化");
        }
        renderLayer.instructions.insert(profileArgument,
            new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                "decorateForRender", PROFILE_DESCRIPTOR, false));

        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}
