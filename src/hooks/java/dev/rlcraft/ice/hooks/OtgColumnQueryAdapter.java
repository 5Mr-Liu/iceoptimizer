package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Corrects column identity/value and binds it to preview-chunk lifetime. */
final class OtgColumnQueryAdapter implements OptimizerBytecodeAdapter {
    static final String OWNER = "com/pg85/otg/forge/generator/OTGChunkGenerator";
    static final String CACHE = "dev/rlcraft/ice/optimizer/compat/otg/OtgColumnCache";
    static final String FIFO = "com/pg85/otg/util/FifoMap";
    static final String COORD = "com/pg85/otg/util/ChunkCoordinate";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes); ClassNode node = new ClassNode(); reader.accept(node, ClassReader.EXPAND_FRAMES);
        int constructors = 0, columns = 0, invalidations = 0;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name)) {
                int allocation = 0;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW && FIFO.equals(((TypeInsnNode) instruction).desc)) {
                        ((TypeInsnNode) instruction).desc = allocation++ == 0 ? CACHE : CACHE + "$PreviewChunks";
                    } else if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (FIFO.equals(call.owner) && "<init>".equals(call.name)) {
                            call.owner = allocation == 1 ? CACHE : CACHE + "$PreviewChunks";
                            if (allocation == 2) {
                                InsnList args = new InsnList(); args.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                args.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "unloadedBlockColumnsCache", "L" + FIFO + ";"));
                                method.instructions.insertBefore(call, args); call.desc = "(IL" + FIFO + ";)V";
                            }
                            constructors++;
                        }
                    }
                }
            } else if ("getBlockColumnInUnloadedChunk".equals(method.name)) {
                transformColumn(method); columns++;
            } else if ("getBlocks".equals(method.name) || "clearChunkFromCache".equals(method.name)) {
                InsnList begin = new InsnList(); begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
                begin.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "unloadedBlockColumnsCache", "L" + FIFO + ";"));
                if ("getBlocks".equals(method.name)) {
                    begin.add(new VarInsnNode(Opcodes.ILOAD, 1)); begin.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COORD, "fromChunkCoords", "(II)L" + COORD + ";", false));
                } else begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
                begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CACHE, "invalidate", "(L" + FIFO + ";L" + COORD + ";)V", false));
                method.instructions.insert(begin); invalidations++;
            }
        }
        if (constructors != 2 || columns != 1 || invalidations != 2) throw new IllegalStateException("OTG column shape changed");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        }; node.accept(writer); return writer.toByteArray();
    }
    private static void transformColumn(MethodNode method) {
        AbstractInsnNode keyNew = null, keyInit = null, cacheStart = null, cacheEnd = null, loadedStore = null;
        int arrays = 0; AbstractInsnNode unusedSize = null, unusedArray = null, unusedStore = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && "com/pg85/otg/util/BlockPos2D".equals(((TypeInsnNode) instruction).desc)) keyNew = instruction;
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("com/pg85/otg/util/BlockPos2D".equals(call.owner) && "<init>".equals(call.name)) keyInit = call;
                if (("getLoadedChunk".equals(call.name) || "func_186026_b".equals(call.name)) && "(II)Lnet/minecraft/world/chunk/Chunk;".equals(call.desc)) loadedStore = next(call);
                if (FIFO.equals(call.owner) && "get".equals(call.name) && cacheStart == null) {
                    cacheStart = previous(previous(previous(call))); cacheEnd = next(next(next(next(next(call)))));
                    // get, checkcast, astore 9, aload 9, ifnull, aload 9, areturn
                    cacheEnd = next(cacheEnd);
                }
                if (FIFO.equals(call.owner) && "put".equals(call.name) && previous(call) instanceof VarInsnNode
                    && ((VarInsnNode) previous(call)).var == 9) ((VarInsnNode) previous(call)).var = 11;
            }
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.ANEWARRAY
                && "com/pg85/otg/common/LocalMaterialData".equals(((TypeInsnNode) instruction).desc)) {
                if (arrays++ == 0) { unusedSize = previous(instruction); unusedArray = instruction; unusedStore = next(instruction); }
            }
        }
        if (keyNew == null || keyInit == null || cacheStart == null || cacheEnd == null || loadedStore == null || arrays != 2)
            throw new IllegalStateException("OTG column body changed");
        method.instructions.remove(next(keyNew)); method.instructions.remove(keyNew);
        method.instructions.set(keyInit, new MethodInsnNode(Opcodes.INVOKESTATIC, CACHE, "key", "(II)Ljava/lang/Long;", false));
        method.instructions.remove(unusedSize); method.instructions.remove(unusedArray); method.instructions.remove(unusedStore);
        // Loaded chunks are mutable: only preview terrain can reuse a saved column.
        InsnList lookup = new InsnList(); LabelNode skip = new LabelNode();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 10)); lookup.add(new JumpInsnNode(Opcodes.IFNONNULL, skip));
        AbstractInsnNode after = cacheEnd.getNext();
        for (AbstractInsnNode instruction = cacheStart; instruction != after;) {
            if (instruction instanceof JumpInsnNode) ((JumpInsnNode) instruction).label = skip;
            AbstractInsnNode next = instruction.getNext(); method.instructions.remove(instruction); lookup.add(instruction); instruction = next;
        }
        lookup.add(skip); method.instructions.insert(loadedStore, lookup);
        if (method.localVariables != null) method.localVariables.clear();
    }
    private static AbstractInsnNode next(AbstractInsnNode n) { do { n = n.getNext(); } while (n != null && n.getOpcode() < 0); return n; }
    private static AbstractInsnNode previous(AbstractInsnNode n) { do { n = n.getPrevious(); } while (n != null && n.getOpcode() < 0); return n; }
}
