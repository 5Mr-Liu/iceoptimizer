package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Structural installation of the primitive setupTerrain traversal ABI. The
 * original Queue BFS remains byte-for-byte reachable whenever the bridge
 * declines ownership.
 */
final class PrimitiveTerrainVisibilityAdapter implements OptimizerBytecodeAdapter {
    enum Part { RENDER_GLOBAL, RENDER_INFO, RENDER_CHUNK, COMPILED_CHUNK, SET_VISIBILITY }

    static final String RENDER_GLOBAL = "net/minecraft/client/renderer/RenderGlobal";
    static final String RENDER_INFO = RENDER_GLOBAL + "$ContainerLocalRenderInformation";
    static final String VIEW_FRUSTUM = "net/minecraft/client/renderer/ViewFrustum";
    static final String RENDER_CHUNK = "net/minecraft/client/renderer/chunk/RenderChunk";
    static final String COMPILED_CHUNK = "net/minecraft/client/renderer/chunk/CompiledChunk";
    static final String SET_VISIBILITY = "net/minecraft/client/renderer/chunk/SetVisibility";
    static final String ENUM_FACING = "net/minecraft/util/EnumFacing";
    static final String BLOCK_POS = "net/minecraft/util/math/BlockPos";
    static final String AABB = "net/minecraft/util/math/AxisAlignedBB";
    static final String CAMERA = "net/minecraft/client/renderer/culling/ICamera";
    static final String CHUNK = "net/minecraft/world/chunk/Chunk";
    private static final String OPTIFINE_CHUNK_VISIBILITY =
        "net/optifine/render/ChunkVisibility";
    private static final String OPTIFINE_CHUNK_UTILS =
        "net/optifine/util/ChunkUtils";

    static final String GLOBAL_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/TerrainVisibilityAccessor";
    static final String INFO_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/TerrainRenderInfoAccessor";
    static final String CHUNK_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/TerrainRenderChunkIndexAccessor";
    static final String COMPILED_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/TerrainCompiledChunkAccessor";
    static final String MASK_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/TerrainVisibilityMaskAccessor";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/render/visibility/PrimitiveTerrainVisibilityBridge";

    static final String SETUP_TERRAIN = "func_174970_a";
    static final String SETUP_TERRAIN_DESC = "(Lnet/minecraft/entity/Entity;DL" + CAMERA
        + ";IZ)V";
    private static final String GET_OFFSET = "func_181562_a";
    private static final String GET_OFFSET_DESC = "(L" + BLOCK_POS + ";L" + RENDER_CHUNK
        + ";L" + ENUM_FACING + ";)L" + RENDER_CHUNK + ";";
    private static final String SET_FRAME = "func_178577_a";
    private static final String SET_FRAME_DESC = "(I)Z";
    private static final String GET_COMPILED = "func_178571_g";
    private static final String GET_COMPILED_DESC = "()L" + COMPILED_CHUNK + ";";
    private static final String IS_VISIBLE = "func_178495_a";
    private static final String IS_VISIBLE_DESC = "(L" + ENUM_FACING + ";L" + ENUM_FACING
        + ";)Z";
    private static final String HAS_DIRECTION = "func_189560_a";
    private static final String HAS_DIRECTION_DESC = "(L" + ENUM_FACING + ";)Z";
    private static final String SET_DIRECTION = "func_189561_a";
    private static final String SET_DIRECTION_DESC = "(BL" + ENUM_FACING + ";)V";
    private static final String COMPILED_EMPTY = "func_178489_a";
    private static final String COMPILED_EMPTY_DESC = "()Z";
    private static final String COMPILED_TILES = "func_178485_b";
    private static final String COMPILED_TILES_DESC = "()Ljava/util/List;";
    private static final String RENDER_CHUNK_NONEMPTY = "func_178569_m";
    private static final String RENDER_CHUNK_NONEMPTY_DESC = "()Z";
    private static final String FRUSTUM_TEST = "func_78546_a";
    private static final String FRUSTUM_TEST_DESC = "(L" + AABB + ";)Z";
    private static final String SET_ONE_VISIBLE = "func_178619_a";
    private static final String SET_ONE_VISIBLE_DESC = "(L" + ENUM_FACING + ";L"
        + ENUM_FACING + ";Z)V";
    private static final String SET_ALL_VISIBLE = "func_178618_a";
    private static final String SET_ALL_VISIBLE_DESC = "(Z)V";
    private static final String MASK_FIELD = "ice$visibilityMask";
    private static final long ALL_VISIBILITY_BITS = (1L << 36) - 1L;

    private final Part part;

    PrimitiveTerrainVisibilityAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        switch (part) {
            case RENDER_GLOBAL: transformRenderGlobal(node); break;
            case RENDER_INFO: transformRenderInfo(node); break;
            case RENDER_CHUNK: transformRenderChunk(node); break;
            case COMPILED_CHUNK: transformCompiledChunk(node); break;
            case SET_VISIBILITY: transformSetVisibility(node); break;
            default: throw new IllegalStateException("primitive visibility adapter");
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformRenderGlobal(ClassNode node) {
        requireClass(node, RENDER_GLOBAL);
        addInterface(node, GLOBAL_ACCESS);
        requireField(node, "field_72755_R", "Ljava/util/List;");
        requireField(node, "field_175008_n", "L" + VIEW_FRUSTUM + ";");
        MethodNode setup = requireMethod(node, SETUP_TERRAIN, SETUP_TERRAIN_DESC);
        TraversalShape shape = resolveTraversalShape(node, setup);
        MethodInsnNode emptyCall = shape.emptyCall;
        AbstractInsnNode queueLoadNode = previousCode(emptyCall);
        AbstractInsnNode emptyJumpNode = nextCode(emptyCall);
        if (!(queueLoadNode instanceof VarInsnNode)
            || queueLoadNode.getOpcode() != Opcodes.ALOAD
            || !(emptyJumpNode instanceof JumpInsnNode)
            || emptyJumpNode.getOpcode() != Opcodes.IFNE) {
            throw new IllegalStateException("setupTerrain Queue loop shape");
        }
        int queueLocal = ((VarInsnNode) queueLoadNode).var;
        LabelNode loopExit = ((JumpInsnNode) emptyJumpNode).label;
        int loopStartIndex = setup.instructions.indexOf(queueLoadNode);
        int loopExitIndex = setup.instructions.indexOf(loopExit);
        if (loopStartIndex < 0 || loopExitIndex <= loopStartIndex) {
            throw new IllegalStateException("setupTerrain Queue loop bounds");
        }

        MethodInsnNode offset = shape.offsetCall;
        List<VarInsnNode> offsetLoads = precedingLoads(offset,
            shape.optifine ? 6 : 4);
        for (int index = 0; index < 4; index++) {
            if (offsetLoads.get(index).getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException("setupTerrain offset object arguments");
            }
        }
        if (offsetLoads.get(0).var != 0
            || (shape.optifine
                && (offsetLoads.get(4).getOpcode() != Opcodes.ILOAD
                    || offsetLoads.get(5).getOpcode() != Opcodes.ILOAD))) {
            throw new IllegalStateException("setupTerrain offset arguments");
        }
        int originLocal = offsetLoads.get(1).var;
        int fogLocal = shape.optifine ? offsetLoads.get(4).var : -1;
        int renderDistanceLocal = shape.optifine ? offsetLoads.get(5).var : -1;

        MethodInsnNode frame = uniqueCallBetween(setup, RENDER_CHUNK, SET_FRAME,
            SET_FRAME_DESC, loopStartIndex, loopExitIndex);
        AbstractInsnNode frameLoad = previousCode(frame);
        if (!(frameLoad instanceof VarInsnNode) || frameLoad.getOpcode() != Opcodes.ILOAD) {
            throw new IllegalStateException("setupTerrain frame argument");
        }
        int frameLocal = ((VarInsnNode) frameLoad).var;

        MethodInsnNode frustum = shape.frustumCall;
        int cameraLocal;
        if (shape.optifine) {
            List<VarInsnNode> loads = precedingLoads(frustum, 3);
            if (loads.get(0).getOpcode() != Opcodes.ALOAD
                || loads.get(1).getOpcode() != Opcodes.ALOAD
                || loads.get(2).getOpcode() != Opcodes.ILOAD
                || loads.get(2).var != frameLocal) {
                throw new IllegalStateException("OptiFine frustum arguments");
            }
            cameraLocal = loads.get(1).var;
        } else {
            AbstractInsnNode bounds = previousCode(frustum);
            AbstractInsnNode chunk = previousCode(bounds);
            AbstractInsnNode camera = previousCode(chunk);
            if (!(bounds instanceof FieldInsnNode)
                || bounds.getOpcode() != Opcodes.GETFIELD
                || !RENDER_CHUNK.equals(((FieldInsnNode) bounds).owner)
                || !"field_178591_c".equals(((FieldInsnNode) bounds).name)
                || !(chunk instanceof VarInsnNode) || chunk.getOpcode() != Opcodes.ALOAD
                || !(camera instanceof VarInsnNode) || camera.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException("setupTerrain frustum arguments");
            }
            cameraLocal = ((VarInsnNode) camera).var;
        }

        AbstractInsnNode directionAnchor = shape.optifine
            ? shape.optifineDirectionCall
            : uniqueCallBetween(setup, RENDER_INFO, HAS_DIRECTION,
                HAS_DIRECTION_DESC, loopStartIndex, loopExitIndex);
        VarInsnNode pathFlagLoad = nearestLoad(directionAnchor, Opcodes.ILOAD, 16);
        if (pathFlagLoad == null) throw new IllegalStateException("setupTerrain path flag");
        int pathFlagLocal = pathFlagLoad.var;

        MethodInsnNode iterationStart = findIterationProfilerCall(setup, loopStartIndex);
        int handledLocal = setup.maxLocals++;
        InsnList decision = new InsnList();
        decision.add(new VarInsnNode(Opcodes.ALOAD, 0));
        decision.add(new VarInsnNode(Opcodes.ALOAD, queueLocal));
        decision.add(new VarInsnNode(Opcodes.ALOAD, originLocal));
        decision.add(new VarInsnNode(Opcodes.ALOAD, cameraLocal));
        decision.add(new VarInsnNode(Opcodes.ILOAD, frameLocal));
        decision.add(new VarInsnNode(Opcodes.ILOAD, pathFlagLocal));
        if (shape.optifine) {
            decision.add(new VarInsnNode(Opcodes.ILOAD, fogLocal));
            decision.add(new VarInsnNode(Opcodes.ILOAD, renderDistanceLocal));
            decision.add(new InsnNode(Opcodes.ICONST_1));
        } else {
            decision.add(new InsnNode(Opcodes.ICONST_0));
            decision.add(new InsnNode(Opcodes.ICONST_0));
            decision.add(new InsnNode(Opcodes.ICONST_0));
        }
        decision.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "tryTraverse",
            "(Ljava/lang/Object;Ljava/util/Queue;Ljava/lang/Object;Ljava/lang/Object;IZZIZ)Z",
            false));
        decision.add(new VarInsnNode(Opcodes.ISTORE, handledLocal));
        decision.add(new VarInsnNode(Opcodes.ILOAD, handledLocal));
        decision.add(new JumpInsnNode(Opcodes.IFNE, loopExit));
        setup.instructions.insert(iterationStart, decision);

        InsnList completion = new InsnList();
        completion.add(new VarInsnNode(Opcodes.ALOAD, 0));
        completion.add(new VarInsnNode(Opcodes.ILOAD, handledLocal));
        completion.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "afterTraversal",
            "(Ljava/lang/Object;Z)V", false));
        setup.instructions.insert(loopExit, completion);

        addGlobalAccessors(node, shape);
    }

    private static TraversalShape resolveTraversalShape(ClassNode node,
                                                         MethodNode setup) {
        MethodInsnNode empty = null;
        MethodInsnNode poll = null;
        int matches = 0;
        for (AbstractInsnNode instruction : setup.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!("java/util/Queue".equals(call.owner)
                    || "java/util/Deque".equals(call.owner))
                || !"isEmpty".equals(call.name) || !"()Z".equals(call.desc)) continue;
            AbstractInsnNode load = previousCode(call);
            AbstractInsnNode jump = nextCode(call);
            if (!(load instanceof VarInsnNode) || load.getOpcode() != Opcodes.ALOAD
                || !(jump instanceof JumpInsnNode) || jump.getOpcode() != Opcodes.IFNE) {
                continue;
            }
            int queueLocal = ((VarInsnNode) load).var;
            LabelNode exit = ((JumpInsnNode) jump).label;
            int startIndex = setup.instructions.indexOf(load);
            int exitIndex = setup.instructions.indexOf(exit);
            MethodInsnNode candidatePoll = null;
            int polls = 0;
            for (AbstractInsnNode nested : setup.instructions.toArray()) {
                int index = setup.instructions.indexOf(nested);
                if (index <= startIndex || index >= exitIndex
                    || !(nested instanceof MethodInsnNode)) continue;
                MethodInsnNode nestedCall = (MethodInsnNode) nested;
                if (!call.owner.equals(nestedCall.owner)
                    || !"poll".equals(nestedCall.name)
                    || !"()Ljava/lang/Object;".equals(nestedCall.desc)) continue;
                AbstractInsnNode pollLoad = previousCode(nestedCall);
                AbstractInsnNode cast = nextCode(nestedCall);
                AbstractInsnNode store = nextCode(cast);
                if (pollLoad instanceof VarInsnNode
                    && pollLoad.getOpcode() == Opcodes.ALOAD
                    && ((VarInsnNode) pollLoad).var == queueLocal
                    && cast instanceof TypeInsnNode
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && RENDER_INFO.equals(((TypeInsnNode) cast).desc)
                    && store instanceof VarInsnNode
                    && store.getOpcode() == Opcodes.ASTORE) {
                    candidatePoll = nestedCall;
                    polls++;
                }
            }
            if (polls == 1) {
                empty = call;
                poll = candidatePoll;
                matches++;
            }
        }
        if (matches != 1 || empty == null || poll == null) {
            throw new IllegalStateException("setupTerrain traversal loop count " + matches);
        }
        int start = setup.instructions.indexOf(previousCode(empty));
        int end = setup.instructions.indexOf(((JumpInsnNode) nextCode(empty)).label);
        String optifineOffsetDesc = "(L" + BLOCK_POS + ";L" + RENDER_CHUNK
            + ";L" + ENUM_FACING + ";ZI)L" + RENDER_CHUNK + ";";
        MethodInsnNode vanillaOffset = findUniqueCallBetween(setup, RENDER_GLOBAL,
            GET_OFFSET, GET_OFFSET_DESC, start, end, false);
        MethodInsnNode optifineOffset = findUniqueCallByOwnerDescBetween(setup,
            RENDER_GLOBAL, optifineOffsetDesc, start, end, false);
        if ((vanillaOffset == null) == (optifineOffset == null)) {
            throw new IllegalStateException("setupTerrain offset mode ambiguity");
        }
        boolean optifine = optifineOffset != null;
        MethodInsnNode frustum = optifine
            ? requireUniqueCallByOwnerDescBetween(setup, RENDER_CHUNK,
                "(L" + CAMERA + ";I)Z", start, end)
            : uniqueCallBetween(setup, CAMERA, FRUSTUM_TEST,
                FRUSTUM_TEST_DESC, start, end);
        TraversalShape shape = new TraversalShape(optifine, empty, poll,
            optifine ? optifineOffset : vanillaOffset, frustum);
        if (!optifine) return shape;

        shape.optifineDirectionCall = requireUniqueCallByOwnerDescBetween(setup,
            OPTIFINE_CHUNK_VISIBILITY, "(I)[L" + ENUM_FACING + ";", start, end);
        shape.getRenderInfo = requireUniqueCallByOwnerDescBetween(setup, RENDER_CHUNK,
            "()L" + RENDER_INFO + ";", start, end);
        shape.initializeRenderInfo = requireUniqueCallByOwnerDescBetween(setup,
            RENDER_INFO, "(L" + RENDER_INFO + ";L" + ENUM_FACING + ";I)V",
            start, end);
        shape.compiledField = uniqueFieldAccessBetween(setup, RENDER_CHUNK,
            "L" + COMPILED_CHUNK + ";", Opcodes.GETFIELD, start, end);
        shape.compiledEmpty = uniqueCallBetween(setup, COMPILED_CHUNK,
            COMPILED_EMPTY, COMPILED_EMPTY_DESC, start, end);
        shape.renderChunkNonempty = uniqueCallBetween(setup, RENDER_CHUNK,
            RENDER_CHUNK_NONEMPTY, RENDER_CHUNK_NONEMPTY_DESC, start, end);
        shape.getChunk = requireUniqueCallByOwnerDescBetween(setup, RENDER_CHUNK,
            "()L" + CHUNK + ";", start, end);
        shape.hasEntities = requireUniqueCallByOwnerDescBetween(setup,
            OPTIFINE_CHUNK_UTILS, "(L" + CHUNK + ";)Z", start, end);
        shape.compiledTiles = uniqueCallBetween(setup, COMPILED_CHUNK,
            COMPILED_TILES, COMPILED_TILES_DESC, start, end);
        shape.mainInfosField = requireField(node, "field_72755_R", "Ljava/util/List;");
        shape.entityInfosField = requireField(node, "renderInfosEntities",
            "Ljava/util/List;");
        shape.tileInfosField = requireField(node, "renderInfosTileEntities",
            "Ljava/util/List;");
        requireFieldReadCount(setup, shape.mainInfosField, start, end, 1);
        requireFieldReadCount(setup, shape.entityInfosField, start, end, 1);
        requireFieldReadCount(setup, shape.tileInfosField, start, end, 1);
        return shape;
    }

    private static void addOptionalListGetter(ClassNode node, String name,
                                              FieldNode field) {
        MethodNode method = newMethod(name, "()Ljava/util/List;");
        if (field == null) method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        else {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
                field.name, field.desc));
        }
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
    }

    private static MethodNode vanillaAppendRenderInfo() {
        MethodNode method = newMethod("ice$appendRenderInfo",
            "(Ljava/lang/Object;Ljava/lang/Object;)V");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_GLOBAL,
            "field_72755_R", "Ljava/util/List;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
            "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    /** Replays OptiFine G5's three observable render-info list updates. */
    private static MethodNode optifineAppendRenderInfo(TraversalShape shape) {
        MethodNode method = newMethod("ice$appendRenderInfo",
            "(Ljava/lang/Object;Ljava/lang/Object;)V");
        LabelNode addMain = new LabelNode();
        LabelNode afterMain = new LabelNode();
        LabelNode afterEntities = new LabelNode();
        LabelNode afterTiles = new LabelNode();
        int chunkLocal = 3;
        int compiledLocal = 4;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RENDER_CHUNK));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, chunkLocal));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
            shape.compiledField.owner, shape.compiledField.name,
            shape.compiledField.desc));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, compiledLocal));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, compiledLocal));
        method.instructions.add(copyCall(shape.compiledEmpty));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, addMain));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        method.instructions.add(copyCall(shape.renderChunkNonempty));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, afterMain));
        method.instructions.add(addMain);
        addToList(method.instructions, shape.mainInfosField, 1);
        method.instructions.add(afterMain);

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        method.instructions.add(copyCall(shape.getChunk));
        method.instructions.add(copyCall(shape.hasEntities));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, afterEntities));
        addToList(method.instructions, shape.entityInfosField, 1);
        method.instructions.add(afterEntities);

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, compiledLocal));
        method.instructions.add(copyCall(shape.compiledTiles));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
            "java/util/List", "size", "()I", true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFLE, afterTiles));
        addToList(method.instructions, shape.tileInfosField, 1);
        method.instructions.add(afterTiles);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static void addToList(InsnList instructions, FieldNode field,
                                  int valueLocal) {
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_GLOBAL,
            field.name, field.desc));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, valueLocal));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
            "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
        instructions.add(new InsnNode(Opcodes.POP));
    }

    private static MethodInsnNode copyCall(MethodInsnNode call) {
        return new MethodInsnNode(call.getOpcode(), call.owner, call.name,
            call.desc, call.itf);
    }

    private static void addGlobalAccessors(ClassNode node, TraversalShape shape) {
        MethodNode infos = newMethod("ice$renderInfos", "()Ljava/util/List;");
        infos.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        infos.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_GLOBAL,
            "field_72755_R", "Ljava/util/List;"));
        infos.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(infos);

        addOptionalListGetter(node, "ice$renderInfosEntities",
            shape.optifine ? shape.entityInfosField : null);
        addOptionalListGetter(node, "ice$renderInfosTileEntities",
            shape.optifine ? shape.tileInfosField : null);

        MethodNode optifine = newMethod("ice$isOptifineTraversal", "()Z");
        optifine.instructions.add(new InsnNode(shape.optifine
            ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        optifine.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(optifine);

        node.methods.add(shape.optifine
            ? optifineAppendRenderInfo(shape)
            : vanillaAppendRenderInfo());

        MethodNode chunks = newMethod("ice$renderChunks", "()[Ljava/lang/Object;");
        chunks.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        chunks.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_GLOBAL,
            "field_175008_n", "L" + VIEW_FRUSTUM + ";"));
        chunks.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VIEW_FRUSTUM,
            "field_178164_f", "[L" + RENDER_CHUNK + ";"));
        chunks.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(chunks);

        MethodNode directions = newMethod("ice$directions", "()[Ljava/lang/Object;");
        directions.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ENUM_FACING,
            "values", "()[L" + ENUM_FACING + ";", false));
        directions.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(directions);

        MethodNode offset = newMethod("ice$getRenderChunkOffset",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;ZI)Ljava/lang/Object;");
        offset.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        offset.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        offset.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, BLOCK_POS));
        offset.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        offset.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RENDER_CHUNK));
        offset.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        offset.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        if (shape.optifine) {
            offset.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
            offset.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
            offset.instructions.add(new MethodInsnNode(shape.offsetCall.getOpcode(),
                RENDER_GLOBAL, shape.offsetCall.name, shape.offsetCall.desc,
                shape.offsetCall.itf));
        } else {
            offset.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RENDER_GLOBAL,
                GET_OFFSET, GET_OFFSET_DESC, false));
        }
        offset.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(offset);

        MethodNode opposite = newMethod("ice$oppositeDirection",
            "(Ljava/lang/Object;)Ljava/lang/Object;");
        opposite.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        opposite.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        opposite.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENUM_FACING,
            "func_176734_d", "()L" + ENUM_FACING + ";", false));
        opposite.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(opposite);

        MethodNode newInfo = newMethod("ice$newRenderInfo",
            "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;");
        if (shape.optifine) {
            newInfo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            newInfo.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RENDER_CHUNK));
            newInfo.instructions.add(new MethodInsnNode(shape.getRenderInfo.getOpcode(),
                RENDER_CHUNK, shape.getRenderInfo.name, shape.getRenderInfo.desc,
                shape.getRenderInfo.itf));
            newInfo.instructions.add(new InsnNode(Opcodes.DUP));
            newInfo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            newInfo.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
            newInfo.instructions.add(new InsnNode(Opcodes.ICONST_0));
            newInfo.instructions.add(new MethodInsnNode(shape.initializeRenderInfo.getOpcode(),
                shape.initializeRenderInfo.owner, shape.initializeRenderInfo.name,
                shape.initializeRenderInfo.desc, shape.initializeRenderInfo.itf));
        } else {
            newInfo.instructions.add(new TypeInsnNode(Opcodes.NEW, RENDER_INFO));
            newInfo.instructions.add(new InsnNode(Opcodes.DUP));
            newInfo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            newInfo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            newInfo.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RENDER_CHUNK));
            newInfo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            newInfo.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
            newInfo.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            newInfo.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RENDER_INFO,
                "<init>", "(L" + RENDER_GLOBAL + ";L" + RENDER_CHUNK + ";L"
                    + ENUM_FACING + ";I)V", false));
        }
        newInfo.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(newInfo);

        MethodNode inFrustum = newMethod("ice$isInFrustum",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Z");
        if (shape.optifine) {
            inFrustum.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            inFrustum.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RENDER_CHUNK));
            inFrustum.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            inFrustum.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, CAMERA));
            inFrustum.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
            inFrustum.instructions.add(new MethodInsnNode(shape.frustumCall.getOpcode(),
                RENDER_CHUNK, shape.frustumCall.name, shape.frustumCall.desc,
                shape.frustumCall.itf));
        } else {
            inFrustum.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            inFrustum.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, CAMERA));
            inFrustum.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            inFrustum.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, AABB));
            inFrustum.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, CAMERA,
                FRUSTUM_TEST, FRUSTUM_TEST_DESC, true));
        }
        inFrustum.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(inFrustum);
    }

    private static void transformRenderInfo(ClassNode node) {
        requireClass(node, RENDER_INFO);
        addInterface(node, INFO_ACCESS);
        FieldNode chunkField = uniqueInstanceField(node, "L" + RENDER_CHUNK + ";");
        FieldNode directionField = uniqueInstanceField(node, "L" + ENUM_FACING + ";");
        FieldNode pathField = optionalUniqueInstanceField(node, "B");
        FieldNode intField = uniqueInstanceField(node, "I");
        boolean optifine = pathField == null;
        if (optifine) pathField = intField;
        requireMethod(node, SET_DIRECTION, SET_DIRECTION_DESC);
        addObjectGetter(node, "ice$renderChunk", chunkField.name,
            "L" + RENDER_CHUNK + ";");
        addObjectGetter(node, "ice$incomingDirection", directionField.name,
            "L" + ENUM_FACING + ";");
        if (optifine) addNarrowingByteGetter(node, "ice$pathDirections", pathField);
        else addPrimitiveGetter(node, "ice$pathDirections", "()B", pathField.name,
            "B", Opcodes.IRETURN);
        if (optifine) addConstantIntGetter(node, "ice$counter", 0);
        else addPrimitiveGetter(node, "ice$counter", "()I", intField.name,
            "I", Opcodes.IRETURN);

        MethodNode set = newMethod("ice$setDirection", "(BLjava/lang/Object;)V");
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        set.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        set.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        set.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDER_INFO,
            SET_DIRECTION, SET_DIRECTION_DESC, false));
        set.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(set);

        MethodNode canonical = newMethod("ice$isCanonicalRenderInfo", "()Z");
        LabelNode no = new LabelNode();
        canonical.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        canonical.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        canonical.instructions.add(new LdcInsnNode(Type.getObjectType(RENDER_INFO)));
        canonical.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, no));
        canonical.instructions.add(new InsnNode(Opcodes.ICONST_1));
        canonical.instructions.add(new InsnNode(Opcodes.IRETURN));
        canonical.instructions.add(no);
        canonical.instructions.add(new InsnNode(Opcodes.ICONST_0));
        canonical.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(canonical);
    }

    private static void transformRenderChunk(ClassNode node) {
        requireClass(node, RENDER_CHUNK);
        addInterface(node, CHUNK_ACCESS);
        requireField(node, "field_178596_j", "I");
        requireField(node, "field_178591_c", "L" + AABB + ";");
        requireMethod(node, SET_FRAME, SET_FRAME_DESC);
        requireMethod(node, GET_COMPILED, GET_COMPILED_DESC);
        addPrimitiveGetter(node, "ice$renderChunkIndex", "()I", "field_178596_j", "I",
            Opcodes.IRETURN);
        addObjectGetter(node, "ice$bounds", "field_178591_c", "L" + AABB + ";");

        MethodNode setFrame = newMethod("ice$setFrameIndex", "(I)Z");
        setFrame.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setFrame.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setFrame.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDER_CHUNK,
            SET_FRAME, SET_FRAME_DESC, false));
        setFrame.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(setFrame);

        MethodNode mask = newMethod("ice$visibilityMask", "()J");
        LabelNode fallback = new LabelNode();
        mask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mask.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDER_CHUNK,
            GET_COMPILED, GET_COMPILED_DESC, false));
        mask.instructions.add(new InsnNode(Opcodes.DUP));
        mask.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, COMPILED_ACCESS));
        mask.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        mask.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, COMPILED_ACCESS));
        mask.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, COMPILED_ACCESS,
            "ice$visibilityMask", "()J", true));
        mask.instructions.add(new InsnNode(Opcodes.LRETURN));
        mask.instructions.add(fallback);
        mask.instructions.add(new InsnNode(Opcodes.POP));
        mask.instructions.add(new LdcInsnNode(Long.valueOf(-1L)));
        mask.instructions.add(new InsnNode(Opcodes.LRETURN));
        node.methods.add(mask);

        MethodNode visible = newMethod("ice$isVisible",
            "(Ljava/lang/Object;Ljava/lang/Object;)Z");
        visible.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        visible.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDER_CHUNK,
            GET_COMPILED, GET_COMPILED_DESC, false));
        visible.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        visible.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        visible.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        visible.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        visible.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMPILED_CHUNK,
            IS_VISIBLE, IS_VISIBLE_DESC, false));
        visible.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(visible);
    }

    private static void transformCompiledChunk(ClassNode node) {
        requireClass(node, COMPILED_CHUNK);
        addInterface(node, COMPILED_ACCESS);
        requireField(node, "field_178496_f", "L" + SET_VISIBILITY + ";");
        requireMethod(node, IS_VISIBLE, IS_VISIBLE_DESC);

        MethodNode mask = newMethod("ice$visibilityMask", "()J");
        LabelNode subclassFallback = new LabelNode();
        LabelNode accessorFallback = new LabelNode();
        mask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mask.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        mask.instructions.add(new LdcInsnNode(Type.getObjectType(COMPILED_CHUNK)));
        mask.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, subclassFallback));
        mask.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mask.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, COMPILED_CHUNK,
            "field_178496_f", "L" + SET_VISIBILITY + ";"));
        mask.instructions.add(new InsnNode(Opcodes.DUP));
        mask.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, MASK_ACCESS));
        mask.instructions.add(new JumpInsnNode(Opcodes.IFEQ, accessorFallback));
        mask.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MASK_ACCESS));
        mask.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MASK_ACCESS,
            "ice$visibilityMask", "()J", true));
        mask.instructions.add(new InsnNode(Opcodes.LRETURN));
        mask.instructions.add(accessorFallback);
        mask.instructions.add(new InsnNode(Opcodes.POP));
        mask.instructions.add(new LdcInsnNode(Long.valueOf(-1L)));
        mask.instructions.add(new InsnNode(Opcodes.LRETURN));
        mask.instructions.add(subclassFallback);
        mask.instructions.add(new LdcInsnNode(Long.valueOf(-1L)));
        mask.instructions.add(new InsnNode(Opcodes.LRETURN));
        node.methods.add(mask);

        MethodNode visible = newMethod("ice$isVisible",
            "(Ljava/lang/Object;Ljava/lang/Object;)Z");
        visible.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        visible.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        visible.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        visible.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        visible.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENUM_FACING));
        visible.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMPILED_CHUNK,
            IS_VISIBLE, IS_VISIBLE_DESC, false));
        visible.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(visible);
    }

    private static void transformSetVisibility(ClassNode node) {
        requireClass(node, SET_VISIBILITY);
        addInterface(node, MASK_ACCESS);
        if (findField(node, MASK_FIELD, "J") != null) {
            throw new IllegalStateException("visibility mask field duplicate");
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
            MASK_FIELD, "J", null, null));
        MethodNode one = requireMethod(node, SET_ONE_VISIBLE, SET_ONE_VISIBLE_DESC);
        MethodNode all = requireMethod(node, SET_ALL_VISIBLE, SET_ALL_VISIBLE_DESC);
        MethodNode helper = visibilityUpdateHelper();
        node.methods.add(helper);
        injectBeforeOnlyReturn(one, visibilityOneUpdate());
        injectBeforeOnlyReturn(all, visibilityAllUpdate());
        addPrimitiveGetter(node, "ice$visibilityMask", "()J", MASK_FIELD, "J",
            Opcodes.LRETURN);
    }

    private static MethodNode visibilityUpdateHelper() {
        MethodNode helper = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$updateVisibility", "(IIZ)V", null, null);
        LabelNode clear = new LabelNode();
        helper.instructions.add(new InsnNode(Opcodes.LCONST_1));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new IntInsn(Opcodes.BIPUSH, 6));
        helper.instructions.add(new InsnNode(Opcodes.IMUL));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.IADD));
        helper.instructions.add(new InsnNode(Opcodes.LSHL));
        helper.instructions.add(new InsnNode(Opcodes.LCONST_1));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        helper.instructions.add(new IntInsn(Opcodes.BIPUSH, 6));
        helper.instructions.add(new InsnNode(Opcodes.IMUL));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new InsnNode(Opcodes.IADD));
        helper.instructions.add(new InsnNode(Opcodes.LSHL));
        helper.instructions.add(new InsnNode(Opcodes.LOR));
        helper.instructions.add(new VarInsnNode(Opcodes.LSTORE, 4));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, clear));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DUP));
        helper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, SET_VISIBILITY,
            MASK_FIELD, "J"));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        helper.instructions.add(new InsnNode(Opcodes.LOR));
        helper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, SET_VISIBILITY,
            MASK_FIELD, "J"));
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.instructions.add(clear);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DUP));
        helper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, SET_VISIBILITY,
            MASK_FIELD, "J"));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        helper.instructions.add(new LdcInsnNode(Long.valueOf(-1L)));
        helper.instructions.add(new InsnNode(Opcodes.LXOR));
        helper.instructions.add(new InsnNode(Opcodes.LAND));
        helper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, SET_VISIBILITY,
            MASK_FIELD, "J"));
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        return helper;
    }

    private static InsnList visibilityOneUpdate() {
        InsnList update = new InsnList();
        update.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.add(new VarInsnNode(Opcodes.ALOAD, 1));
        update.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENUM_FACING,
            "ordinal", "()I", false));
        update.add(new VarInsnNode(Opcodes.ALOAD, 2));
        update.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENUM_FACING,
            "ordinal", "()I", false));
        update.add(new VarInsnNode(Opcodes.ILOAD, 3));
        update.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SET_VISIBILITY,
            "ice$updateVisibility", "(IIZ)V", false));
        return update;
    }

    private static InsnList visibilityAllUpdate() {
        InsnList update = new InsnList();
        LabelNode clear = new LabelNode();
        LabelNode assign = new LabelNode();
        update.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.add(new VarInsnNode(Opcodes.ILOAD, 1));
        update.add(new JumpInsnNode(Opcodes.IFEQ, clear));
        update.add(new LdcInsnNode(Long.valueOf(ALL_VISIBILITY_BITS)));
        update.add(new JumpInsnNode(Opcodes.GOTO, assign));
        update.add(clear);
        update.add(new InsnNode(Opcodes.LCONST_0));
        update.add(assign);
        update.add(new FieldInsnNode(Opcodes.PUTFIELD, SET_VISIBILITY,
            MASK_FIELD, "J"));
        return update;
    }

    private static MethodInsnNode findIterationProfilerCall(MethodNode method, int beforeIndex) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof LdcInsnNode)
                || !"iteration".equals(((LdcInsnNode) instruction).cst)
                || method.instructions.indexOf(instruction) >= beforeIndex) continue;
            AbstractInsnNode next = nextCode(instruction);
            if (next instanceof MethodInsnNode
                && "func_76320_a".equals(((MethodInsnNode) next).name)
                && "(Ljava/lang/String;)V".equals(((MethodInsnNode) next).desc)) {
                if (found != null) throw new IllegalStateException("iteration profiler duplicate");
                found = (MethodInsnNode) next;
            }
        }
        if (found == null) throw new IllegalStateException("iteration profiler missing");
        return found;
    }

    private static void injectBeforeOnlyReturn(MethodNode method, InsnList update) {
        AbstractInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                found = instruction;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(method.name + " return count " + count);
        method.instructions.insertBefore(found, update);
    }

    private static void addInterface(ClassNode node, String access) {
        if (node.interfaces.contains(access)) throw new IllegalStateException("ABI duplicate " + access);
        node.interfaces.add(access);
    }

    private static MethodNode newMethod(String name, String descriptor) {
        return new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
    }

    private static void addObjectGetter(ClassNode node, String methodName,
                                        String fieldName, String fieldDescriptor) {
        MethodNode method = newMethod(methodName, "()Ljava/lang/Object;");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
            fieldName, fieldDescriptor));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
    }

    private static void addPrimitiveGetter(ClassNode node, String methodName,
                                           String descriptor, String fieldName,
                                           String fieldDescriptor, int returnOpcode) {
        MethodNode method = newMethod(methodName, descriptor);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
            fieldName, fieldDescriptor));
        method.instructions.add(new InsnNode(returnOpcode));
        node.methods.add(method);
    }

    private static void addNarrowingByteGetter(ClassNode node, String methodName,
                                                FieldNode field) {
        MethodNode method = newMethod(methodName, "()B");
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
            field.name, field.desc));
        method.instructions.add(new InsnNode(Opcodes.I2B));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static void addConstantIntGetter(ClassNode node, String methodName,
                                             int value) {
        if (value != 0) throw new IllegalArgumentException("constant getter");
        MethodNode method = newMethod(methodName, "()I");
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static List<VarInsnNode> precedingLoads(AbstractInsnNode instruction, int count) {
        List<VarInsnNode> reverse = new ArrayList<VarInsnNode>(count);
        AbstractInsnNode cursor = instruction;
        while (reverse.size() < count && (cursor = previousCode(cursor)) != null) {
            if (!(cursor instanceof VarInsnNode)
                || (cursor.getOpcode() != Opcodes.ALOAD
                    && cursor.getOpcode() != Opcodes.ILOAD)) {
                throw new IllegalStateException("non-load call argument");
            }
            reverse.add((VarInsnNode) cursor);
        }
        List<VarInsnNode> result = new ArrayList<VarInsnNode>(reverse.size());
        for (int i = reverse.size() - 1; i >= 0; i--) result.add(reverse.get(i));
        return result;
    }

    private static VarInsnNode nearestLoad(AbstractInsnNode instruction, int opcode,
                                           int maximumInstructions) {
        AbstractInsnNode cursor = instruction;
        for (int i = 0; i < maximumInstructions && (cursor = previousCode(cursor)) != null; i++) {
            if (cursor instanceof VarInsnNode && cursor.getOpcode() == opcode) {
                return (VarInsnNode) cursor;
            }
        }
        return null;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner,
                                             String name, String descriptor) {
        return uniqueCallBetween(method, owner, name, descriptor, -1,
            Integer.MAX_VALUE);
    }

    private static MethodInsnNode findUniqueCallBetween(MethodNode method,
                                                         String owner,
                                                         String name,
                                                         String descriptor,
                                                         int minimumIndex,
                                                         int maximumIndex,
                                                         boolean required) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int index = method.instructions.indexOf(instruction);
            if (index <= minimumIndex || index >= maximumIndex
                || !(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) {
                found = call;
                count++;
            }
        }
        if (count > 1 || (required && count != 1)) {
            throw new IllegalStateException(name + " call count " + count);
        }
        return found;
    }

    private static MethodInsnNode findUniqueCallByOwnerDescBetween(
        MethodNode method, String owner, String descriptor, int minimumIndex,
        int maximumIndex, boolean required) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int index = method.instructions.indexOf(instruction);
            if (index <= minimumIndex || index >= maximumIndex
                || !(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && descriptor.equals(call.desc)) {
                found = call;
                count++;
            }
        }
        if (count > 1 || (required && count != 1)) {
            throw new IllegalStateException(owner + descriptor
                + " call count " + count);
        }
        return found;
    }

    private static MethodInsnNode requireUniqueCallByOwnerDescBetween(
        MethodNode method, String owner, String descriptor, int minimumIndex,
        int maximumIndex) {
        return findUniqueCallByOwnerDescBetween(method, owner, descriptor,
            minimumIndex, maximumIndex, true);
    }

    private static FieldInsnNode uniqueFieldAccessBetween(MethodNode method,
                                                           String owner,
                                                           String descriptor,
                                                           int opcode,
                                                           int minimumIndex,
                                                           int maximumIndex) {
        FieldInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int index = method.instructions.indexOf(instruction);
            if (index <= minimumIndex || index >= maximumIndex
                || !(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.getOpcode() == opcode && owner.equals(field.owner)
                && descriptor.equals(field.desc)) {
                found = field;
                count++;
            }
        }
        if (count != 1 || found == null) {
            throw new IllegalStateException(owner + descriptor
                + " field access count " + count);
        }
        return found;
    }

    private static void requireFieldReadCount(MethodNode method, FieldNode field,
                                              int minimumIndex, int maximumIndex,
                                              int expected) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int index = method.instructions.indexOf(instruction);
            if (index <= minimumIndex || index >= maximumIndex
                || !(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode read = (FieldInsnNode) instruction;
            if (read.getOpcode() == Opcodes.GETFIELD
                && RENDER_GLOBAL.equals(read.owner)
                && field.name.equals(read.name) && field.desc.equals(read.desc)) count++;
        }
        if (count != expected) {
            throw new IllegalStateException(field.name + " read count " + count);
        }
    }

    private static MethodInsnNode uniqueCallBetween(MethodNode method, String owner,
                                                    String name, String descriptor,
                                                    int minimumIndex, int maximumIndex) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int index = method.instructions.indexOf(instruction);
            if (index <= minimumIndex || index >= maximumIndex
                || !(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) {
                found = call;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(name + " call count " + count);
        return found;
    }

    private static void requireClass(ClassNode node, String expected) {
        if (!expected.equals(node.name)) throw new IllegalStateException("target " + node.name);
    }

    private static FieldNode requireField(ClassNode node, String name, String descriptor) {
        FieldNode field = findField(node, name, descriptor);
        if (field == null) throw new IllegalStateException("missing field " + name + descriptor);
        return field;
    }

    private static FieldNode uniqueInstanceField(ClassNode node, String descriptor) {
        FieldNode found = optionalUniqueInstanceField(node, descriptor);
        if (found == null) throw new IllegalStateException("missing instance field "
            + descriptor);
        return found;
    }

    private static FieldNode optionalUniqueInstanceField(ClassNode node,
                                                          String descriptor) {
        FieldNode found = null;
        int count = 0;
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0
                && descriptor.equals(field.desc)) {
                found = field;
                count++;
            }
        }
        if (count > 1) throw new IllegalStateException("instance field "
            + descriptor + " count " + count);
        return found;
    }

    private static FieldNode findField(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) return field;
        }
        return null;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode method = findMethod(node, name, descriptor);
        if (method == null) throw new IllegalStateException("missing method " + name + descriptor);
        return method;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static final class IntInsn extends org.objectweb.asm.tree.IntInsnNode {
        private IntInsn(int opcode, int operand) { super(opcode, operand); }
    }

    private static final class TraversalShape {
        private final boolean optifine;
        private final MethodInsnNode emptyCall;
        @SuppressWarnings("unused") private final MethodInsnNode pollCall;
        private final MethodInsnNode offsetCall;
        private final MethodInsnNode frustumCall;
        private MethodInsnNode optifineDirectionCall;
        private MethodInsnNode getRenderInfo;
        private MethodInsnNode initializeRenderInfo;
        private FieldInsnNode compiledField;
        private MethodInsnNode compiledEmpty;
        private MethodInsnNode renderChunkNonempty;
        private MethodInsnNode getChunk;
        private MethodInsnNode hasEntities;
        private MethodInsnNode compiledTiles;
        private FieldNode mainInfosField;
        private FieldNode entityInfosField;
        private FieldNode tileInfosField;

        private TraversalShape(boolean optifine, MethodInsnNode emptyCall,
                               MethodInsnNode pollCall,
                               MethodInsnNode offsetCall,
                               MethodInsnNode frustumCall) {
            this.optifine = optifine;
            this.emptyCall = emptyCall;
            this.pollCall = pollCall;
            this.offsetCall = offsetCall;
            this.frustumCall = frustumCall;
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
