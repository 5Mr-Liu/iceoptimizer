package dev.rlcraft.ice.hooks;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Cold installation: one preparation per terrain frame, no old filter loop. */
final class NativeTerrainFrameAdapter {
    static final String RENDERER = "dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainRenderer";
    static final String VISIBILITY = "dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainVisibility";

    static void install(ClassNode owner, MethodNode setup) {
        NativeTerrainChunkScheduleAdapter.install(owner, setup);
        owner.interfaces.add("dev/rlcraft/ice/optimizer/compat/chunk/NativeTerrainWorldAccessor");
        MethodNode cameraSection = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$cameraSection", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        cameraSection.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cameraSection.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, "field_175008_n",
            "Lnet/minecraft/client/renderer/ViewFrustum;"));
        cameraSection.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cameraSection.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/util/math/BlockPos"));
        cameraSection.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/renderer/ViewFrustum",
            "func_178161_a", "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/client/renderer/chunk/RenderChunk;", false));
        cameraSection.instructions.add(new InsnNode(Opcodes.ARETURN));
        owner.methods.add(cameraSection);
        MethodNode stopUpdates = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$stopChunkUpdates", "()V", null, null);
        stopUpdates.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stopUpdates.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.name,
            "func_174986_e", "()V", false));
        stopUpdates.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(stopUpdates);
        InsnList begin = new InsnList();
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDERER, "beginFrame", "()V", false));
        setup.instructions.insert(begin);
        MethodNode layer = null;
        for (MethodNode m : owner.methods)
            if (m.name.equals("func_174977_a") && m.desc.equals("(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I")) layer = m;
        if (layer == null) throw new IllegalStateException("native terrain layer entry missing");
        MethodNode incomingLayer = NativeTerrainPauseAdapter.copy(layer);
        MethodInsnNode start = null, add = null;
        for (AbstractInsnNode n : layer.instructions.toArray()) {
            if (n instanceof LdcInsnNode && "filterempty".equals(((LdcInsnNode) n).cst))
                start = (MethodInsnNode) codeAfter(n);
            if (n instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) n;
                if (call.owner.equals(ModernTerrainAdapter.CONTAINER) && call.name.equals("func_178002_a")) add = call;
            }
        }
        if (start == null || add == null) throw new IllegalStateException("native terrain layer filter shape");
        AbstractInsnNode first = codeAfter(start);
        AbstractInsnNode store = codeAfter(first);
        if (!(store instanceof VarInsnNode) || store.getOpcode() != Opcodes.ISTORE)
            throw new IllegalStateException("native terrain layer count local");
        int countLocal = ((VarInsnNode) store).var;
        AbstractInsnNode end = add;
        while (end != null && end.getOpcode() != Opcodes.GOTO) end = end.getNext();
        if (end == null) throw new IllegalStateException("native terrain layer filter end");
        AbstractInsnNode after = end.getNext();
        FieldInsnNode container = null;
        for (AbstractInsnNode n = add.getPrevious(); n != start; n = n.getPrevious())
            if (n instanceof FieldInsnNode && ((FieldInsnNode) n).desc.equals("L" + ModernTerrainAdapter.CONTAINER + ";")) {
                container = (FieldInsnNode) n; break;
            }
        if (container == null) throw new IllegalStateException("native terrain container field");
        InsnList prepare = new InsnList();
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepare.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, container.name, container.desc));
        prepare.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDERER, "prepareLayer",
            "(Ljava/lang/Object;Ljava/lang/Object;Lnet/minecraft/util/BlockRenderLayer;)I", false));
        prepare.add(new VarInsnNode(Opcodes.ISTORE, countLocal));
        layer.instructions.insertBefore(first, prepare);
        for (AbstractInsnNode n = first; n != after;) {
            AbstractInsnNode next = n.getNext(); layer.instructions.remove(n); n = next;
        }
        if (layer.localVariables != null) layer.localVariables.clear();
        NativeTerrainPauseAdapter.install(owner, layer, incomingLayer);
    }

    private static AbstractInsnNode codeAfter(AbstractInsnNode n) {
        do { n = n.getNext(); } while (n != null && n.getOpcode() < 0);
        return n;
    }
}
