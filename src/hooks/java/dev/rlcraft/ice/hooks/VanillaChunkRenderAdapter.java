package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Structurally installs the generic vanilla chunk worker, sort and VBO paths. */
final class VanillaChunkRenderAdapter implements OptimizerBytecodeAdapter {
    enum Part {
        DISPATCH_POLICY,
        DISPATCH_UPLOAD,
        BUFFER_SORT,
        VERTEX_BUFFER_ACCESS
    }

    static final String DISPATCHER = "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher";
    static final String BUFFER_BUILDER = "net/minecraft/client/renderer/BufferBuilder";
    static final String VERTEX_BUFFER = "net/minecraft/client/renderer/vertex/VertexBuffer";
    static final String BUFFER_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/ChunkBufferAccessor";
    static final String VBO_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/ChunkVertexBufferAccessor";
    static final String POLICY_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/chunk/ChunkDispatcherPolicyAccessor";
    static final String POLICY_BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/chunk/ChunkRenderPolicyBridge";
    static final String SORT_BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/chunk/ChunkPrimitiveSortBridge";
    static final String UPLOAD_BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/chunk/ChunkVboUploadBridge";

    static final String SORT_METHOD = "func_181674_a";
    static final String SORT_DESCRIPTOR = "(FFF)V";
    static final String ORIGINAL_SORT = "ice$originalSortVertexData";
    static final String UPLOAD_METHOD = "func_178506_a";
    static final String UPLOAD_DESCRIPTOR =
        "(Lnet/minecraft/client/renderer/BufferBuilder;"
            + "Lnet/minecraft/client/renderer/vertex/VertexBuffer;)V";
    static final String ORIGINAL_UPLOAD = "ice$originalUploadVertexBuffer";
    static final String BUFFER_DATA_METHOD = "func_181722_a";
    static final String BUFFER_DATA_DESCRIPTOR = "(Ljava/nio/ByteBuffer;)V";
    static final String ORIGINAL_BUFFER_DATA = "ice$originalBufferData";
    static final String CAPACITY_FIELD = "ice$capacityBytes";

    private static final String RAW_INTS = "field_178999_b";
    private static final String RAW_FLOATS = "field_179000_c";
    private static final String VERTEX_COUNT = "field_178997_d";
    private static final String VERTEX_FORMAT = "field_179011_q";
    private static final String GL_BUFFER_ID = "field_177365_a";
    private static final String VBO_FORMAT = "field_177363_b";
    private static final String VBO_COUNT = "field_177364_c";
    private static final String VERTEX_FORMAT_OWNER =
        "net/minecraft/client/renderer/vertex/VertexFormat";
    private static final String DISPATCHER_BUILDER_COUNT = "field_188249_c";
    private final Part part;

    VanillaChunkRenderAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        switch (part) {
            case DISPATCH_POLICY:
                requireTarget(node, DISPATCHER);
                transformDispatcherPolicy(node);
                break;
            case DISPATCH_UPLOAD:
                requireTarget(node, DISPATCHER);
                transformDispatcherUpload(node);
                break;
            case BUFFER_SORT:
                requireTarget(node, BUFFER_BUILDER);
                transformBufferBuilder(node);
                break;
            case VERTEX_BUFFER_ACCESS:
                requireTarget(node, VERTEX_BUFFER);
                transformVertexBuffer(node);
                break;
            default:
                throw new IllegalStateException("未知区块渲染能力：" + part);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformDispatcherPolicy(ClassNode node) {
        rejectInterface(node, POLICY_ACCESS);
        MethodNode constructor = requireMethod(node, "<init>", "(I)V");
        List<FieldWrite> builderWrites = constructorReachableFieldWrites(node, constructor,
            Opcodes.PUTFIELD, node.name, DISPATCHER_BUILDER_COUNT, "I");
        MethodInsnNode processors = uniqueCall(constructor, Opcodes.INVOKEVIRTUAL,
            "java/lang/Runtime", "availableProcessors", "()I");
        AbstractInsnNode firstDirectWrite = null;
        for (FieldWrite builderWrite : builderWrites) {
            if (builderWrite.method != constructor) continue;
            if (!comesBefore(processors, builderWrite.instruction)) {
                throw new IllegalStateException("区块 worker 与构建器初始化顺序变化");
            }
            if (firstDirectWrite == null
                || comesBefore(builderWrite.instruction, firstDirectWrite)) {
                firstDirectWrite = builderWrite.instruction;
            }
        }
        VarInsnNode workerStore = nextStoreBefore(processors, firstDirectWrite);
        if (workerStore == null) throw new IllegalStateException("区块 worker 计数局部变量变化");
        InsnList tuneWorkers = new InsnList();
        tuneWorkers.add(new VarInsnNode(Opcodes.ILOAD, workerStore.var));
        tuneWorkers.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY_BRIDGE,
            "tuneWorkerCount", "(I)I", false));
        tuneWorkers.add(new VarInsnNode(Opcodes.ISTORE, workerStore.var));
        constructor.instructions.insert(workerStore, tuneWorkers);

        node.interfaces.add(POLICY_ACCESS);
        addIntFieldAccessor(node, "ice$builderCount", DISPATCHER_BUILDER_COUNT);
        addIntFieldSetter(node, "ice$setBuilderCount", DISPATCHER_BUILDER_COUNT);
        for (FieldWrite builderWrite : builderWrites) {
            InsnList clampBuilders = new InsnList();
            clampBuilders.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clampBuilders.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY_BRIDGE,
                "clampBuilderCount", "(L" + POLICY_ACCESS + ";)V", false));
            builderWrite.method.instructions.insert(builderWrite.instruction, clampBuilders);
        }
    }

    private static void transformDispatcherUpload(ClassNode node) {
        rejectMethod(node, ORIGINAL_UPLOAD, UPLOAD_DESCRIPTOR);
        MethodNode upload = requireMethod(node, UPLOAD_METHOD, UPLOAD_DESCRIPTOR);
        if (countCalls(upload, "net/minecraft/client/renderer/VertexBufferUploader",
            "func_178178_a", "(Lnet/minecraft/client/renderer/vertex/VertexBuffer;)V") != 1
            || countCalls(upload, "net/minecraft/client/renderer/VertexBufferUploader",
                "func_181679_a", "(Lnet/minecraft/client/renderer/BufferBuilder;)V") != 1) {
            throw new IllegalStateException("区块 VBO 上传调用图变化");
        }
        wrapDispatcherUpload(node, upload);
    }

    private static void requireTarget(ClassNode node, String expected) {
        if (!expected.equals(node.name)) {
            throw new IllegalStateException("区块渲染目标类变化：" + node.name
                + "，期望 " + expected);
        }
    }

    private static void wrapDispatcherUpload(ClassNode node, MethodNode original) {
        int access = original.access;
        String signature = original.signature;
        String[] exceptions = exceptions(original);
        original.name = ORIGINAL_UPLOAD;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, UPLOAD_METHOD,
            UPLOAD_DESCRIPTOR, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, UPLOAD_BRIDGE,
            "tryUpload", UPLOAD_DESCRIPTOR.substring(0, UPLOAD_DESCRIPTOR.length() - 1) + "Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, DISPATCHER,
            ORIGINAL_UPLOAD, UPLOAD_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);
    }

    private static void transformBufferBuilder(ClassNode node) {
        rejectInterface(node, BUFFER_ACCESS);
        requireField(node, RAW_INTS, "Ljava/nio/IntBuffer;");
        requireField(node, RAW_FLOATS, "Ljava/nio/FloatBuffer;");
        requireField(node, VERTEX_COUNT, "I");
        requireField(node, VERTEX_FORMAT, "L" + VERTEX_FORMAT_OWNER + ";");
        rejectMethod(node, ORIGINAL_SORT, SORT_DESCRIPTOR);
        MethodNode sort = requireMethod(node, SORT_METHOD, SORT_DESCRIPTOR);
        if (countCalls(sort, "java/util/Arrays", "sort",
            "([Ljava/lang/Object;Ljava/util/Comparator;)V") != 1
            || countTypeInstructions(sort, Opcodes.ANEWARRAY, "java/lang/Integer") != 1) {
            throw new IllegalStateException("BufferBuilder 透明排序调用图变化");
        }
        node.interfaces.add(BUFFER_ACCESS);
        int access = sort.access;
        String signature = sort.signature;
        String[] exceptions = exceptions(sort);
        sort.name = ORIGINAL_SORT;
        sort.access = (sort.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, SORT_METHOD,
            SORT_DESCRIPTOR, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SORT_BRIDGE,
            "trySort", "(L" + BUFFER_ACCESS + ";FFF)Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BUFFER_BUILDER,
            ORIGINAL_SORT, SORT_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);
        addReferenceAccessor(node, "ice$sortFloatBuffer", "()Ljava/nio/FloatBuffer;",
            RAW_FLOATS, "Ljava/nio/FloatBuffer;");
        addReferenceAccessor(node, "ice$sortIntBuffer", "()Ljava/nio/IntBuffer;",
            RAW_INTS, "Ljava/nio/IntBuffer;");
        addIntFieldAccessor(node, "ice$sortVertexCount", VERTEX_COUNT);
        MethodNode stride = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$sortVertexStrideInts", "()I", null, null);
        stride.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stride.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BUFFER_BUILDER,
            VERTEX_FORMAT, "L" + VERTEX_FORMAT_OWNER + ";"));
        stride.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VERTEX_FORMAT_OWNER,
            "func_181719_f", "()I", false));
        stride.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(stride);
    }

    private static void transformVertexBuffer(ClassNode node) {
        rejectInterface(node, VBO_ACCESS);
        requireField(node, GL_BUFFER_ID, "I");
        requireField(node, VBO_FORMAT, "L" + VERTEX_FORMAT_OWNER + ";");
        requireField(node, VBO_COUNT, "I");
        rejectField(node, CAPACITY_FIELD);
        rejectMethod(node, ORIGINAL_BUFFER_DATA, BUFFER_DATA_DESCRIPTOR);
        MethodNode bufferData = requireMethod(node, BUFFER_DATA_METHOD, BUFFER_DATA_DESCRIPTOR);
        if (countCalls(bufferData, "net/minecraft/client/renderer/OpenGlHelper",
            "func_176071_a", "(ILjava/nio/ByteBuffer;I)V") != 1
            || countCalls(bufferData, "java/nio/ByteBuffer", "limit", "()I") != 1) {
            throw new IllegalStateException("VertexBuffer.bufferData 调用图变化");
        }
        node.interfaces.add(VBO_ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT
            | Opcodes.ACC_SYNTHETIC, CAPACITY_FIELD, "I", null, null));
        int access = bufferData.access;
        String signature = bufferData.signature;
        String[] exceptions = exceptions(bufferData);
        bufferData.name = ORIGINAL_BUFFER_DATA;
        bufferData.access = (bufferData.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, BUFFER_DATA_METHOD,
            BUFFER_DATA_DESCRIPTOR, signature, exceptions);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, VERTEX_BUFFER,
            ORIGINAL_BUFFER_DATA, BUFFER_DATA_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "java/nio/ByteBuffer", "limit", "()I", false));
        wrapper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, VERTEX_BUFFER,
            CAPACITY_FIELD, "I"));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);

        MethodNode delete = requireMethod(node, "func_177362_c", "()V");
        InsnList resetCapacity = new InsnList();
        resetCapacity.add(new VarInsnNode(Opcodes.ALOAD, 0));
        resetCapacity.add(new InsnNode(Opcodes.ICONST_0));
        resetCapacity.add(new FieldInsnNode(Opcodes.PUTFIELD, VERTEX_BUFFER,
            CAPACITY_FIELD, "I"));
        delete.instructions.insert(resetCapacity);

        addIntFieldAccessor(node, "ice$glBufferId", GL_BUFFER_ID);
        addIntFieldAccessor(node, "ice$capacityBytes", CAPACITY_FIELD);
        addIntFieldSetter(node, "ice$setCapacityBytes", CAPACITY_FIELD);
        addIntFieldSetter(node, "ice$setVertexCount", VBO_COUNT);
        MethodNode stride = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$vertexStrideBytes", "()I", null, null);
        stride.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stride.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VERTEX_BUFFER,
            VBO_FORMAT, "L" + VERTEX_FORMAT_OWNER + ";"));
        stride.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VERTEX_FORMAT_OWNER,
            "func_177338_f", "()I", false));
        stride.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(stride);
    }

    private static void addReferenceAccessor(ClassNode node, String name, String descriptor,
                                             String field, String fieldDescriptor) {
        rejectMethod(node, name, descriptor);
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, fieldDescriptor));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method);
    }

    private static void addIntFieldAccessor(ClassNode node, String name, String field) {
        rejectMethod(node, name, "()I");
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, "()I", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, "I"));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
    }

    private static void addIntFieldSetter(ClassNode node, String name, String field) {
        rejectMethod(node, name, "(I)V");
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, "(I)V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, field, "I"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode, String owner,
                                             String name, String descriptor) {
        MethodInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == opcode && owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) {
                found = call;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(method.name + " 调用数量变化："
            + owner + '.' + name + descriptor + '=' + count);
        return found;
    }

    private static FieldInsnNode uniqueField(MethodNode method, int opcode, String owner,
                                             String name, String descriptor) {
        FieldInsnNode found = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.getOpcode() == opcode && owner.equals(field.owner)
                && name.equals(field.name) && descriptor.equals(field.desc)) {
                found = field;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(method.name + " 字段写入数量变化："
            + owner + '.' + name + descriptor + '=' + count);
        return found;
    }

    private static List<FieldWrite> constructorReachableFieldWrites(ClassNode node,
            MethodNode constructor, int opcode, String owner, String name, String descriptor) {
        List<FieldWrite> reachableWrites = new ArrayList<FieldWrite>();
        List<MethodNode> pending = new ArrayList<MethodNode>();
        Set<String> visited = new HashSet<String>();
        pending.add(constructor);
        for (int index = 0; index < pending.size(); index++) {
            MethodNode method = pending.get(index);
            String key = method.name + method.desc;
            if (!visited.add(key)) continue;
            if ((method.access & Opcodes.ACC_STATIC) != 0) {
                throw new IllegalStateException("区块构建器初始化 helper 不再是实例方法：" + key);
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (field.getOpcode() == opcode && owner.equals(field.owner)
                        && name.equals(field.name) && descriptor.equals(field.desc)) {
                        reachableWrites.add(new FieldWrite(method, field));
                    }
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!node.name.equals(call.owner) || "<init>".equals(call.name)) continue;
                    MethodNode called = findMethod(node, call.name, call.desc);
                    if (called != null && (called.access & Opcodes.ACC_PRIVATE) != 0) {
                        pending.add(called);
                    }
                }
            }
        }
        int allWrites = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode)) continue;
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name) && descriptor.equals(field.desc)) allWrites++;
            }
        }
        if (reachableWrites.isEmpty() || reachableWrites.size() != allWrites) {
            throw new IllegalStateException(node.name + " 构建器字段写入无法证明只属于构造路径："
                + reachableWrites.size() + '/' + allWrites);
        }
        return reachableWrites;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static int countCalls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int countTypeInstructions(MethodNode method, int opcode, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == opcode
                && type.equals(((TypeInsnNode) instruction).desc)) count++;
        }
        return count;
    }

    private static VarInsnNode nextStoreBefore(AbstractInsnNode start, AbstractInsnNode boundary) {
        for (AbstractInsnNode current = start.getNext(); current != null && current != boundary;
             current = current.getNext()) {
            if (current instanceof VarInsnNode && current.getOpcode() == Opcodes.ISTORE) {
                return (VarInsnNode) current;
            }
        }
        return null;
    }

    private static boolean comesBefore(AbstractInsnNode start, AbstractInsnNode expectedLater) {
        for (AbstractInsnNode current = start.getNext(); current != null; current = current.getNext()) {
            if (current == expectedLater) return true;
        }
        return false;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode result = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                result = method;
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException(node.name + " 方法数量变化："
            + name + descriptor + '=' + count);
        return result;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 ICE 方法 " + name + descriptor);
            }
        }
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        int count = 0;
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) count++;
        }
        if (count != 1) throw new IllegalStateException(node.name + " 字段数量变化："
            + name + descriptor + '=' + count);
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) throw new IllegalStateException(node.name + " 已存在字段 " + name);
        }
    }

    private static void rejectInterface(ClassNode node, String name) {
        if (node.interfaces.contains(name)) throw new IllegalStateException(node.name + " 已实现 " + name);
    }

    @SuppressWarnings("unchecked")
    private static String[] exceptions(MethodNode method) {
        List<String> values = method.exceptions;
        return values == null ? null : values.toArray(new String[values.size()]);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }

    private static final class FieldWrite {
        private final MethodNode method;
        private final FieldInsnNode instruction;

        private FieldWrite(MethodNode method, FieldInsnNode instruction) {
            this.method = method;
            this.instruction = instruction;
        }
    }
}
