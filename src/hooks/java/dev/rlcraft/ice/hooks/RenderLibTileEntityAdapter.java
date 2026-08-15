package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Entry guard for the exact RenderLib tile-entity list processing helper. */
final class RenderLibTileEntityAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET_METHOD = "processTileEntities";
    static final String DREGORA_TARGET_METHOD = "processTileEntityList";
    static final String TARGET_DESCRIPTOR = "(Lnet/minecraft/world/World;Ljava/util/function/Consumer;)V";
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/optimizer/compat/renderlib/RenderLibTileEntityBridge";
    static final String BRIDGE_DESCRIPTOR = "(Lnet/minecraft/world/World;Ljava/util/function/Consumer;)Z";
    static final String BRIDGE_METHOD = "tryProcess";
    static final String DREGORA_BRIDGE_METHOD = "tryProcessHolder";
    private static final String HOLDER_OWNER = "meldexun/renderlib/util/ITileEntityHolder";
    private static final String HOLDER_GETTER = "getTileEntities";
    private static final String HOLDER_GETTER_DESCRIPTOR = "()Ljava/util/List;";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("RenderLib 目标类名变化：" + node.name);
        }

        MethodNode legacy = null;
        MethodNode holder = null;
        int legacyMatches = 0;
        int holderMatches = 0;
        for (MethodNode method : node.methods) {
            if (!TARGET_DESCRIPTOR.equals(method.desc)) continue;
            if (TARGET_METHOD.equals(method.name)) {
                legacy = method;
                legacyMatches++;
            } else if (DREGORA_TARGET_METHOD.equals(method.name)) {
                holder = method;
                holderMatches++;
            }
        }

        if (legacyMatches != 1 || holderMatches > 1) {
            throw new IllegalStateException("RenderLib 精确处理方法数量变化：legacy="
                + legacyMatches + ", holder=" + holderMatches);
        }
        MethodNode selected = holderMatches == 1 ? holder : legacy;
        String bridgeMethod = holderMatches == 1 ? DREGORA_BRIDGE_METHOD : BRIDGE_METHOD;
        if ((selected.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("RenderLib 目标方法不再是 static：" + selected.name);
        }
        if (holderMatches == 1 && countHolderGetterCalls(holder) != 1) {
            throw new IllegalStateException("RenderLib holder 列表调用图变化");
        }

        LabelNode originalImplementation = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER, bridgeMethod,
            BRIDGE_DESCRIPTOR, false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, originalImplementation));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(originalImplementation);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        selected.instructions.insert(guard);

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int countHolderGetterCalls(MethodNode method) {
        int calls = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE && HOLDER_OWNER.equals(call.owner)
                && HOLDER_GETTER.equals(call.name) && HOLDER_GETTER_DESCRIPTOR.equals(call.desc)) {
                calls++;
            }
        }
        return calls;
    }
}
