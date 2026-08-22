package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Structural hooks for upload metadata and the final vanilla VBO draw emitter. */
final class ModernTerrainAdapter implements OptimizerBytecodeAdapter {
    enum Part { UPLOAD_CONTEXT, CONTAINER_ACCESS, VBO_RENDER_LIST }

    static final String DISPATCHER =
        "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher";
    static final String CONTAINER = "net/minecraft/client/renderer/ChunkRenderContainer";
    static final String VBO_RENDER_LIST = "net/minecraft/client/renderer/VboRenderList";
    static final String ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/TerrainRenderListAccessor";
    static final String UPLOAD_CONTEXT =
        "dev/rlcraft/ice/optimizer/render/terrain/TerrainUploadContext";
    static final String RENDER_BRIDGE =
        "dev/rlcraft/ice/optimizer/render/terrain/ModernTerrainBridge";
    static final String LAYER = "net/minecraft/util/BlockRenderLayer";
    static final String RENDER_CHUNK = "net/minecraft/client/renderer/chunk/RenderChunk";
    static final String COMPILED_CHUNK = "net/minecraft/client/renderer/chunk/CompiledChunk";
    static final String UPLOAD_CHUNK = "func_188245_a";
    static final String UPLOAD_CHUNK_DESC = "(L" + LAYER
        + ";Lnet/minecraft/client/renderer/BufferBuilder;L" + RENDER_CHUNK
        + ";L" + COMPILED_CHUNK
        + ";D)Lcom/google/common/util/concurrent/ListenableFuture;";
    static final String UPLOAD_VBO = "func_178506_a";
    static final String UPLOAD_VBO_DESC =
        "(Lnet/minecraft/client/renderer/BufferBuilder;"
            + "Lnet/minecraft/client/renderer/vertex/VertexBuffer;)V";
    static final String RENDER_LAYER = "func_178001_a";
    static final String RENDER_LAYER_DESC = "(L" + LAYER + ";)V";
    static final String ORIGINAL_RENDER = "ice$originalModernRenderChunkLayer";

    private static final String SET_VIEW_POSITION_DESC = "(DDD)V";
    private final Part part;

    ModernTerrainAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        switch (part) {
            case UPLOAD_CONTEXT: transformUploadContext(node); break;
            case CONTAINER_ACCESS: transformContainer(node); break;
            case VBO_RENDER_LIST: transformRenderList(node); break;
            default: throw new IllegalStateException("modern terrain adapter");
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformUploadContext(ClassNode node) {
        requireClass(node, DISPATCHER);
        MethodNode upload = requireMethod(node, UPLOAD_CHUNK, UPLOAD_CHUNK_DESC);
        MethodInsnNode target = uniqueCall(upload, DISPATCHER, UPLOAD_VBO, UPLOAD_VBO_DESC);
        InsnList context = new InsnList();
        context.add(new VarInsnNode(Opcodes.ALOAD, 1));
        context.add(new VarInsnNode(Opcodes.ALOAD, 3));
        context.add(new VarInsnNode(Opcodes.ALOAD, 4));
        context.add(new MethodInsnNode(Opcodes.INVOKESTATIC, UPLOAD_CONTEXT, "begin",
            "(L" + LAYER + ";L" + RENDER_CHUNK + ";L" + COMPILED_CHUNK + ";)V", false));
        upload.instructions.insertBefore(target, context);
    }

    private static void transformContainer(ClassNode node) {
        requireClass(node, CONTAINER);
        if (node.interfaces.contains(ACCESS)) throw new IllegalStateException("container ABI duplicate");
        FieldNode[] view = resolveViewFields(node);
        FieldNode chunks = uniqueInstanceField(node, "Ljava/util/List;");
        FieldNode initialized = uniqueInstanceField(node, "Z");
        node.interfaces.add(ACCESS);
        addGetter(node, "ice$viewEntityX", "()D", view[0].name, "D", Opcodes.DRETURN);
        addGetter(node, "ice$viewEntityY", "()D", view[1].name, "D", Opcodes.DRETURN);
        addGetter(node, "ice$viewEntityZ", "()D", view[2].name, "D", Opcodes.DRETURN);
        addGetter(node, "ice$renderChunks", "()Ljava/util/List;", chunks.name,
            "Ljava/util/List;", Opcodes.ARETURN);
        addGetter(node, "ice$initialized", "()Z", initialized.name, "Z", Opcodes.IRETURN);
    }

    /**
     * OptiFine G5 rebuilds ChunkRenderContainer and reuses the vanilla notch
     * field names for different descriptors.  SRG names are consequently no
     * longer a valid identity.  The public set-position method remains the
     * semantic authority: each DLOAD argument must be written directly to one
     * distinct instance double field.
     */
    private static FieldNode[] resolveViewFields(ClassNode node) {
        MethodNode setter = null;
        int methods = 0;
        for (MethodNode method : node.methods) {
            if (SET_VIEW_POSITION_DESC.equals(method.desc)
                && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_NATIVE)) == 0) {
                setter = method;
                methods++;
            }
        }
        if (methods != 1 || setter == null) {
            throw new IllegalStateException("container position method count " + methods);
        }
        FieldNode[] result = new FieldNode[3];
        for (AbstractInsnNode instruction : setter.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode)
                || instruction.getOpcode() != Opcodes.PUTFIELD) continue;
            FieldInsnNode write = (FieldInsnNode) instruction;
            if (!node.name.equals(write.owner) || !"D".equals(write.desc)) continue;
            AbstractInsnNode value = previousCode(write);
            AbstractInsnNode owner = previousCode(value);
            if (!(value instanceof VarInsnNode)
                || value.getOpcode() != Opcodes.DLOAD
                || !(owner instanceof VarInsnNode)
                || owner.getOpcode() != Opcodes.ALOAD
                || ((VarInsnNode) owner).var != 0) continue;
            int slot = ((VarInsnNode) value).var;
            int index = slot == 1 ? 0 : slot == 3 ? 1 : slot == 5 ? 2 : -1;
            if (index < 0 || result[index] != null) {
                throw new IllegalStateException("container position write ambiguity");
            }
            result[index] = requireDeclaredField(node, write.name, "D");
        }
        if (result[0] == null || result[1] == null || result[2] == null
            || result[0] == result[1] || result[0] == result[2]
            || result[1] == result[2]) {
            throw new IllegalStateException("container position fields incomplete");
        }
        return result;
    }

    private static FieldNode uniqueInstanceField(ClassNode node, String descriptor) {
        FieldNode found = null;
        int count = 0;
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0
                && descriptor.equals(field.desc)) {
                found = field;
                count++;
            }
        }
        if (count != 1 || found == null) {
            throw new IllegalStateException("container instance field " + descriptor
                + " count " + count);
        }
        return found;
    }

    private static void transformRenderList(ClassNode node) {
        requireClass(node, VBO_RENDER_LIST);
        if (findMethod(node, ORIGINAL_RENDER, RENDER_LAYER_DESC) != null) {
            throw new IllegalStateException("modern VBO wrapper duplicate");
        }
        MethodNode original = requireMethod(node, RENDER_LAYER, RENDER_LAYER_DESC);
        int access = original.access;
        String signature = original.signature;
        String[] exceptions = original.exceptions == null ? null
            : original.exceptions.toArray(new String[original.exceptions.size()]);
        original.name = ORIGINAL_RENDER;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, RENDER_LAYER,
            RENDER_LAYER_DESC, signature, exceptions);
        LabelNode fallback = new LabelNode();
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            RENDER_BRIDGE, "beginRender", "(L" + LAYER + ";)J", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.LSTORE, 2));
        wrapper.instructions.add(start);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDER_BRIDGE,
            "tryRender", "(Ljava/lang/Object;L" + LAYER + ";)Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            RENDER_BRIDGE, "endRender", "(J)V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, VBO_RENDER_LIST,
            ORIGINAL_RENDER, RENDER_LAYER_DESC, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDER_BRIDGE,
            "afterRender", "(Ljava/lang/Object;L" + LAYER + ";)V", false));
        wrapper.instructions.add(end);
        wrapper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            RENDER_BRIDGE, "endRender", "(J)V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(handler);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        wrapper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            RENDER_BRIDGE, "endRender", "(J)V", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        wrapper.instructions.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler,
            "java/lang/Throwable"));
        node.methods.add(wrapper);
    }

    private static void addGetter(ClassNode node, String name, String descriptor,
                                  String field, String fieldDescriptor, int returnOpcode) {
        if (findMethod(node, name, descriptor) != null) throw new IllegalStateException(name);
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
            field, fieldDescriptor));
        method.instructions.add(new InsnNode(returnOpcode));
        node.methods.add(method);
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner,
                                             String name, String descriptor) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) {
                found = call;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("upload VBO call count " + count);
        return found;
    }

    private static void requireClass(ClassNode node, String expected) {
        if (!expected.equals(node.name)) throw new IllegalStateException("target " + node.name);
    }

    private static FieldNode requireDeclaredField(ClassNode node, String name,
                                                  String descriptor) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) return field;
        }
        throw new IllegalStateException("missing field " + name + descriptor);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
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

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
