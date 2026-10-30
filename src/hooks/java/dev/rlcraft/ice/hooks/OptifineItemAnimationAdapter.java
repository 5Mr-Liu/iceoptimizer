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

/** Keep item sprites ticking without enabling OptiFine's terrain sprites. */
final class OptifineItemAnimationAdapter implements OptimizerBytecodeAdapter {
    static final String MAP = "net/minecraft/client/renderer/texture/TextureMap";
    static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String POLICY = "dev/rlcraft/ice/hooks/OptifineItemAnimationPolicy";
    static final String GATE = "isTerrainAnimationActive";
    static final String DESC = "(L" + SPRITE + ";)Z";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!MAP.equals(node.name)) throw new IllegalStateException("Unexpected item animation target");
        MethodNode gate = null;
        for (MethodNode method : node.methods) {
            if (!GATE.equals(method.name)) continue;
            if (gate != null || !DESC.equals(method.desc)
                || method.access != Opcodes.ACC_PRIVATE) {
                throw new IllegalStateException("OptiFine terrain animation gate changed");
            }
            gate = method;
        }
        if (gate == null) throw new OptimizerAdapterSkippedException("OptiFine terrain animation gate absent");
        MethodInsnNode terrain = null;
        for (AbstractInsnNode instruction : gate.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (POLICY.equals(call.owner)) throw new IllegalStateException("Item animation gate already adapted");
            if (!"isAnimatedTerrain".equals(call.name)) continue;
            if (terrain != null || call.getOpcode() != Opcodes.INVOKESTATIC
                || !"Config".equals(call.owner) || !"()Z".equals(call.desc)) {
                throw new IllegalStateException("OptiFine terrain option call changed");
            }
            terrain = call;
        }
        AbstractInsnNode next = terrain == null ? null : terrain.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        if (next == null || next.getOpcode() != Opcodes.IRETURN) {
            throw new IllegalStateException("OptiFine terrain option is no longer the fallback return");
        }
        // Only widen the generic fallback. Water/lava/fire/portal and the
        // clock/compass exceptions retain their original branches. All four
        // atlas, multi-texture, normal and specular traversals use this gate.
        InsnList keepItems = new InsnList();
        keepItems.add(new VarInsnNode(Opcodes.ALOAD, 1));
        keepItems.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SPRITE,
            "func_94215_i", "()Ljava/lang/String;", false));
        keepItems.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY,
            "allowAnimation", "(ZLjava/lang/String;)Z", false));
        gate.instructions.insert(terrain, keepItems);
        // The insertion has the same net stack effect and adds no branches.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
