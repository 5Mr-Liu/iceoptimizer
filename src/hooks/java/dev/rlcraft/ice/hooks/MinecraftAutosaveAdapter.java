package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Marks only the scheduled save and advances its original chunk writers on the server thread. */
final class MinecraftAutosaveAdapter implements OptimizerBytecodeAdapter {
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String PROVIDER = "net/minecraft/world/gen/ChunkProviderServer";
    static final String WORLD = "net/minecraft/world/WorldServer";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/save/AutosaveAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/save/AutosaveBridge";
    static final String CURSOR = "ice$autosaveCursor";
    private static final String CHUNK = "(Lnet/minecraft/world/chunk/Chunk;)V";
    public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5); reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (SERVER.equals(node.name)) server(node);
        else if (PROVIDER.equals(node.name)) provider(node);
        else if (WORLD.equals(node.name)) world(node);
        else throw new IllegalStateException(node.name);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
    private static void server(ClassNode node) {
        int replaced = 0;
        MethodNode tick = method(node, "func_71217_p", "()V");
        for (AbstractInsnNode in : tick.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) in;
            if (SERVER.equals(call.owner) && "func_71267_a".equals(call.name) && "(Z)V".equals(call.desc)) {
                call.setOpcode(Opcodes.INVOKESTATIC); call.owner = BRIDGE; call.name = "automaticSave";
                call.desc = "(L" + SERVER + ";Z)V"; call.itf = false; replaced++;
            }
        }
        if (replaced != 1) throw new IllegalStateException("scheduled world save call changed");
        MethodNode save = method(node, "func_71267_a", "(Z)V");
        int automatic = invocationIntent(save);
        replaced = 0;
        for (AbstractInsnNode in : save.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) in;
            if (WORLD.equals(call.owner) && "func_73044_a".equals(call.name)
                && "(ZLnet/minecraft/util/IProgressUpdate;)V".equals(call.desc)) {
                save.instructions.insertBefore(call, new VarInsnNode(Opcodes.ILOAD, automatic));
                call.setOpcode(Opcodes.INVOKESTATIC); call.owner = BRIDGE; call.name = "saveWorld";
                call.desc = "(L" + WORLD + ";ZLnet/minecraft/util/IProgressUpdate;Z)V"; call.itf = false; replaced++;
            }
        }
        if (replaced != 1) throw new IllegalStateException("server world-save call changed");
    }
    private static void world(ClassNode node) {
        MethodNode save = method(node, "func_73044_a", "(ZLnet/minecraft/util/IProgressUpdate;)V");
        int automatic = invocationIntent(save), replaced = 0;
        for (AbstractInsnNode in : save.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) in;
            if (PROVIDER.equals(call.owner) && "func_186027_a".equals(call.name) && "(Z)Z".equals(call.desc)) {
                save.instructions.insertBefore(call, new VarInsnNode(Opcodes.ILOAD, automatic));
                call.setOpcode(Opcodes.INVOKESTATIC); call.owner = BRIDGE; call.name = "saveWorldChunks";
                call.desc = "(L" + PROVIDER + ";ZZ)Z"; call.itf = false; replaced++;
            }
        }
        if (replaced != 1) throw new IllegalStateException("world chunk-save call changed");
    }
    private static int invocationIntent(MethodNode method) {
        int local = method.maxLocals++;
        InsnList prefix = new InsnList();
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "takeAutomatic", "(Ljava/lang/Object;)Z", false));
        prefix.add(new VarInsnNode(Opcodes.ISTORE, local));
        method.instructions.insert(prefix);
        // Existing stack-map types stay exact; the new invocation-local flag is live on every path.
        for (AbstractInsnNode in : method.instructions.toArray()) if (in instanceof FrameNode) {
            FrameNode frame = (FrameNode) in;
            frame.local = new java.util.ArrayList<Object>(frame.local);
            int slots = 0;
            for (Object value : frame.local) slots += Opcodes.LONG.equals(value) || Opcodes.DOUBLE.equals(value) ? 2 : 1;
            while (slots++ < local) frame.local.add(Opcodes.TOP);
            frame.local.add(Opcodes.INTEGER);
        }
        return local;
    }
    private static void provider(ClassNode node) {
        if (node.interfaces.contains(ACCESS)) throw new IllegalStateException("duplicate autosave adapter");
        MethodNode save = method(node, "func_186027_a", "(Z)Z");
        MethodNode tick = method(node, "func_73156_b", "()Z");
        MethodNode flush = method(node, "func_104112_b", "()V");
        method(node, "func_73242_b", CHUNK); method(node, "func_73243_a", CHUNK);
        node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            CURSOR, "Ljava/lang/Object;", null, null));
        save.name = "ice$originalSaveChunks"; save.access |= Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC, "func_186027_a", "(Z)Z", null, null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "save", "(L" + ACCESS + ";Z)Z", false));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN)); node.methods.add(wrapper);
        prefix(tick, "tick"); prefix(flush, "finish");
        MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            CURSOR, "()Ljava/lang/Object;", null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, PROVIDER, CURSOR, "Ljava/lang/Object;"));
        get.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(get);
        MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            CURSOR, "(Ljava/lang/Object;)V", null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, PROVIDER, CURSOR, "Ljava/lang/Object;"));
        set.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(set);
        callback(node, "ice$loadedChunks", "func_189548_a", "()Ljava/util/Collection;", Opcodes.ARETURN, false);
        callback(node, "ice$canSave", "func_73157_c", "()Z", Opcodes.IRETURN, false);
        callback(node, "ice$saveChunkData", "func_73242_b", CHUNK, Opcodes.RETURN, true);
        callback(node, "ice$saveChunkExtraData", "func_73243_a", CHUNK, Opcodes.RETURN, true);
        MethodNode loaded = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$isLoaded", "(Lnet/minecraft/world/chunk/Chunk;)Z", null, null);
        loaded.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loaded.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, PROVIDER, "field_73244_f",
            "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"));
        loaded.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        loaded.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isLoaded",
            "(Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/world/chunk/Chunk;)Z", false));
        loaded.instructions.add(new InsnNode(Opcodes.IRETURN)); node.methods.add(loaded);
    }
    private static void prefix(MethodNode method, String name) {
        InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, name, "(L" + ACCESS + ";)V", false));
        method.instructions.insert(code);
    }
    private static void callback(ClassNode node, String name, String target, String desc, int returns, boolean chunk) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (chunk) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(chunk ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL,
            PROVIDER, target, desc, false));
        method.instructions.add(new InsnNode(returns)); node.methods.add(method);
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && desc.equals(method.desc)) return method;
        throw new IllegalStateException(node.name + "." + name + desc);
    }
}
