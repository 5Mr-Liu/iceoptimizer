package dev.rlcraft.ice.hooks;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Installs the native renderer's rebuild scheduling at RenderGlobal call sites. */
final class NativeTerrainChunkScheduleAdapter {
    private static final String DISPATCHER = "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher";
    private static final String CHUNK = "net/minecraft/client/renderer/chunk/RenderChunk";
    private static final String REBUILD_DESC = "(L" + CHUNK + ";)Z";

    static void install(ClassNode owner, MethodNode setup) {
        MethodNode updates = null;
        for (MethodNode method : owner.methods)
            if (method.name.equals("func_174967_a") && method.desc.equals("(J)V")) updates = method;
        if (updates == null) throw new IllegalStateException("native chunk update entry missing");
        MethodNode incoming = NativeTerrainPauseAdapter.copy(updates);

        int nearCalls = 0;
        for (AbstractInsnNode node : setup.instructions.toArray()) {
            if (!isCall(node, "func_178505_b", REBUILD_DESC)) continue;
            MethodInsnNode call = (MethodInsnNode) node;
            AbstractInsnNode chunkLoad = previousCode(call);
            AbstractInsnNode discard = nextCode(call);
            AbstractInsnNode clearLoad = nextCode(discard);
            AbstractInsnNode clear = nextCode(clearLoad);
            if (!(chunkLoad instanceof VarInsnNode) || chunkLoad.getOpcode() != Opcodes.ALOAD
                || discard == null || discard.getOpcode() != Opcodes.POP
                || !(clearLoad instanceof VarInsnNode) || clearLoad.getOpcode() != Opcodes.ALOAD
                || ((VarInsnNode) chunkLoad).var != ((VarInsnNode) clearLoad).var
                || !(clear instanceof MethodInsnNode)
                || !((MethodInsnNode) clear).owner.equals(CHUNK)
                || !((MethodInsnNode) clear).name.equals("func_188282_m")
                || !((MethodInsnNode) clear).desc.equals("()V"))
                throw new IllegalStateException("native near-rebuild dirty flag shape changed");
            call.name = "func_178507_a"; // Existing distance-priority worker queue.
            LabelNode rejected = new LabelNode();
            setup.instructions.set(discard, new JumpInsnNode(Opcodes.IFEQ, rejected));
            setup.instructions.insert(clear, rejected);
            nearCalls++;
        }
        if (nearCalls != 1) throw new IllegalStateException("native near-rebuild call count changed");

        // Capture the caller's frame deadline before OptiFine extends its local
        // value by 100 ms. Only normal frame uploads get this slice: dispatcher
        // shutdown/F6 drains retain their full completion semantics.
        int frameDeadline = updates.maxLocals;
        updates.maxLocals += 2;
        InsnList capture = new InsnList();
        capture.add(new VarInsnNode(Opcodes.LLOAD, 1));
        capture.add(new VarInsnNode(Opcodes.LSTORE, frameDeadline));
        updates.instructions.insert(capture);
        int uploads = 0, immediateCalls = 0;
        for (AbstractInsnNode node : updates.instructions.toArray()) {
            if (isCall(node, "func_178505_b", REBUILD_DESC)) {
                // updateChunks already checks success before clearing dirty
                // flags/removing work, so queue rejection keeps the retry path.
                ((MethodInsnNode) node).name = "func_178507_a";
                immediateCalls++;
            }
            if (!isCall(node, "func_178516_a", "(J)Z")) continue;
            InsnList deadline = new InsnList();
            deadline.add(new InsnNode(Opcodes.POP2));
            deadline.add(new VarInsnNode(Opcodes.LLOAD, frameDeadline));
            deadline.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainChunkScheduler",
                "uploadDeadline", "(J)J", false));
            updates.instructions.insertBefore(node, deadline);
            uploads++;
        }
        if (uploads != 1 || immediateCalls > 1)
            throw new IllegalStateException("native frame chunk-update shape changed");
        NativeTerrainPauseAdapter.install(owner, updates, incoming);
    }

    private static boolean isCall(AbstractInsnNode node, String name, String descriptor) {
        if (!(node instanceof MethodInsnNode) || node.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        MethodInsnNode call = (MethodInsnNode) node;
        return call.owner.equals(DISPATCHER) && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        if (node == null) return null;
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}
