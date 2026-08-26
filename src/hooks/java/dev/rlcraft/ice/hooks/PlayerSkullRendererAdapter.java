package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Applies the player-skull lookup policy at the renderer shared by every skull path. */
final class PlayerSkullRendererAdapter implements OptimizerBytecodeAdapter {
    private static final String TARGET_CLASS =
        "net/minecraft/client/renderer/tileentity/TileEntitySkullRenderer";
    private static final String RENDER_DESCRIPTOR =
        "(FFFLnet/minecraft/util/EnumFacing;FILcom/mojang/authlib/GameProfile;IF)V";
    private static final String SKIN_MANAGER =
        "net/minecraft/client/resources/SkinManager";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MANAGER_DESCRIPTOR =
        "()Lnet/minecraft/client/resources/SkinManager;";
    private static final String CACHE_DESCRIPTOR =
        "(Lcom/mojang/authlib/GameProfile;)Ljava/util/Map;";
    private static final String BRIDGE_OWNER =
        "dev/rlcraft/ice/optimizer/compat/skull/SkullProfileBridge";
    private static final String BRIDGE_CACHE_DESCRIPTOR =
        "(Lnet/minecraft/client/resources/SkinManager;Lcom/mojang/authlib/GameProfile;)Ljava/util/Map;";
    private static final String PROFILE_DESCRIPTOR =
        "(Lcom/mojang/authlib/GameProfile;)Lcom/mojang/authlib/GameProfile;";
    private static final int PROFILE_LOCAL = 7;

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!TARGET_CLASS.equals(node.name)
            || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("TileEntitySkullRenderer 类名变化：" + node.name);
        }

        MethodNode renderSkull = null;
        int renderMethods = 0;
        for (MethodNode method : node.methods) {
            if (("func_188190_a".equals(method.name) || "renderSkull".equals(method.name))
                && RENDER_DESCRIPTOR.equals(method.desc)) {
                renderSkull = method;
                renderMethods++;
            }
        }
        if (renderMethods != 1 || renderSkull == null) {
            throw new IllegalStateException("TileEntitySkullRenderer 渲染方法匹配数量应为 1，实际 "
                + renderMethods);
        }

        MethodInsnNode skinLookup = null;
        int skinLookups = 0;
        for (AbstractInsnNode instruction : renderSkull.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && SKIN_MANAGER.equals(call.owner)
                && ("func_152788_a".equals(call.name) || "loadSkinFromCache".equals(call.name))
                && CACHE_DESCRIPTOR.equals(call.desc)) {
                skinLookup = call;
                skinLookups++;
            }
        }
        if (skinLookups != 1 || skinLookup == null) {
            throw new IllegalStateException("TileEntitySkullRenderer 皮肤缓存调用数量应为 1，实际 "
                + skinLookups);
        }

        AbstractInsnNode profileArgument = previousOpcode(skinLookup);
        AbstractInsnNode managerLookup = previousOpcode(profileArgument);
        MethodInsnNode managerCall = managerLookup instanceof MethodInsnNode
            ? (MethodInsnNode) managerLookup : null;
        if (!(profileArgument instanceof VarInsnNode)
            || profileArgument.getOpcode() != Opcodes.ALOAD
            || ((VarInsnNode) profileArgument).var != PROFILE_LOCAL
            || managerCall == null
            || managerCall.getOpcode() != Opcodes.INVOKEVIRTUAL
            || !MINECRAFT.equals(managerCall.owner)
            || !("func_152342_ad".equals(managerCall.name)
                || "getSkinManager".equals(managerCall.name))
            || !MANAGER_DESCRIPTOR.equals(managerCall.desc)) {
            throw new IllegalStateException("TileEntitySkullRenderer 皮肤缓存参数装载顺序变化");
        }

        InsnList decorate = new InsnList();
        decorate.add(new VarInsnNode(Opcodes.ALOAD, PROFILE_LOCAL));
        decorate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
            "decorateForRender", PROFILE_DESCRIPTOR, false));
        decorate.add(new VarInsnNode(Opcodes.ASTORE, PROFILE_LOCAL));
        renderSkull.instructions.insertBefore(profileArgument, decorate);

        skinLookup.setOpcode(Opcodes.INVOKESTATIC);
        skinLookup.owner = BRIDGE_OWNER;
        skinLookup.name = "loadSkinFromCacheForRender";
        skinLookup.desc = BRIDGE_CACHE_DESCRIPTOR;
        skinLookup.itf = false;

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
