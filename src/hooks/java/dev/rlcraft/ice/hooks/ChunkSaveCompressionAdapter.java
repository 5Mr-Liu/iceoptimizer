package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

/** Installs post-snapshot compression scheduling and the RegionFile raw ABI. */
final class ChunkSaveCompressionAdapter implements OptimizerBytecodeAdapter {
    enum Part { ANVIL_PIPELINE, REGION_RAW_WRITE }

    static final String ANVIL = "net/minecraft/world/chunk/storage/AnvilChunkLoader";
    static final String REGION = "net/minecraft/world/chunk/storage/RegionFile";
    static final String REGION_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/save/RegionFileRawWriteAccessor";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/save/ChunkSaveCompressionBridge";
    static final String CHUNK_POS = "net/minecraft/util/math/ChunkPos";
    static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    static final String ADD_PENDING = "func_75824_a";
    static final String ADD_PENDING_DESC = "(L" + CHUNK_POS + ";L" + NBT + ";)V";
    static final String WRITE_DATA = "func_183013_b";
    static final String WRITE_DATA_DESC = ADD_PENDING_DESC;
    static final String ORIGINAL_WRITE = "ice$original$writeChunkData";
    static final String REGION_WRITE = "func_76706_a";
    static final String REGION_WRITE_DESC = "(II[BI)V";
    static final String REGION_OUTPUT = "func_76710_b";
    static final String REGION_OUTPUT_DESC = "(II)Ljava/io/DataOutputStream;";
    static final String RAW_ACCESS_METHOD = "ice$writeCompressed";
    private static final String SAVE_DIRECTORY = "field_75825_d";
    private final Part part;

    ChunkSaveCompressionAdapter(Part part) {
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (part == Part.ANVIL_PIPELINE) {
            requireClass(node, transformedName, ANVIL);
            transformAnvil(node);
        } else {
            requireClass(node, transformedName, REGION);
            transformRegion(node);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformAnvil(ClassNode node) {
        rejectMethod(node, ORIGINAL_WRITE, WRITE_DATA_DESC);
        MethodNode pending = requireMethod(node, ADD_PENDING, ADD_PENDING_DESC);
        MethodInsnNode put = uniqueCall(pending, Opcodes.INVOKEINTERFACE,
            "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        AbstractInsnNode discard = nextOpcode(put);
        if (discard == null || discard.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException("AnvilChunkLoader pending map put 结果处理变化");
        }
        InsnList schedule = new InsnList();
        schedule.add(new VarInsnNode(Opcodes.ALOAD, 2));
        schedule.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "schedule", "(L" + NBT + ";)V", false));
        pending.instructions.insert(discard, schedule);

        MethodNode write = requireMethod(node, WRITE_DATA, WRITE_DATA_DESC);
        if (countCalls(write, Opcodes.INVOKESTATIC,
            "net/minecraft/world/chunk/storage/RegionFileCache", "func_76552_d",
            "(Ljava/io/File;II)Ljava/io/DataOutputStream;") != 1
            || countCalls(write, Opcodes.INVOKESTATIC,
                "net/minecraft/nbt/CompressedStreamTools", "func_74800_a",
                "(L" + NBT + ";Ljava/io/DataOutput;)V") != 1) {
            throw new IllegalStateException("AnvilChunkLoader NBT 写入调用图变化");
        }
        int access = write.access;
        String signature = write.signature;
        @SuppressWarnings("unchecked")
        List<String> exceptionList = write.exceptions;
        String[] exceptions = exceptionList == null
            ? null : exceptionList.toArray(new String[exceptionList.size()]);
        write.name = ORIGINAL_WRITE;
        write.access = (write.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, WRITE_DATA,
            WRITE_DATA_DESC, signature, exceptions);
        LabelNode fallback = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ANVIL,
            SAVE_DIRECTORY, "Ljava/io/File;"));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "tryWrite", "(Ljava/io/File;L" + CHUNK_POS + ";L" + NBT + ";)Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(fallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ANVIL,
            ORIGINAL_WRITE, WRITE_DATA_DESC, false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);
    }

    private static void transformRegion(ClassNode node) {
        if (node.interfaces.contains(REGION_ACCESS)) {
            throw new IllegalStateException("RegionFile 已存在 ICE raw-write 接口");
        }
        rejectMethod(node, RAW_ACCESS_METHOD, REGION_WRITE_DESC);
        MethodNode raw = requireMethod(node, REGION_WRITE, REGION_WRITE_DESC);
        if ((raw.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
            throw new IllegalStateException("RegionFile 原始写方法不再同步");
        }
        MethodNode output = requireMethod(node, REGION_OUTPUT, REGION_OUTPUT_DESC);
        TypeInsnNode allocation = null;
        MethodInsnNode constructor = null;
        int allocations = 0;
        int constructors = 0;
        for (AbstractInsnNode instruction : output.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                && "java/util/zip/DeflaterOutputStream".equals(((TypeInsnNode) instruction).desc)) {
                allocation = (TypeInsnNode) instruction;
                allocations++;
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "java/util/zip/DeflaterOutputStream".equals(call.owner)
                    && "<init>".equals(call.name)
                    && "(Ljava/io/OutputStream;)V".equals(call.desc)) {
                    constructor = call;
                    constructors++;
                }
            }
        }
        if (allocations != 1 || constructors != 1 || allocation == null || constructor == null) {
            throw new IllegalStateException("RegionFile Deflater 构造图变化：new="
                + allocations + ", init=" + constructors);
        }
        AbstractInsnNode duplicate = nextOpcode(allocation);
        if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) {
            throw new IllegalStateException("RegionFile Deflater NEW/DUP 栈形状变化");
        }
        output.instructions.remove(allocation);
        output.instructions.remove(duplicate);
        output.instructions.set(constructor, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "createDeflaterStream", "(Ljava/io/OutputStream;)Ljava/io/OutputStream;", false));

        node.interfaces.add(REGION_ACCESS);
        MethodNode accessor = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            RAW_ACCESS_METHOD, REGION_WRITE_DESC, null, null);
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        accessor.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        accessor.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        accessor.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        accessor.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, REGION,
            REGION_WRITE, REGION_WRITE_DESC, false));
        accessor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(accessor);
    }

    private static void requireClass(ClassNode node, String transformedName, String expected) {
        if (!expected.equals(node.name)
            || !expected.equals(transformedName.replace('.', '/'))) {
            throw new IllegalStateException("区块保存目标类变化：" + node.name
                + "，期望 " + expected);
        }
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                match = method;
                count++;
            }
        }
        if (count != 1 || match == null) {
            throw new IllegalStateException(node.name + '.' + name + descriptor
                + " 匹配数量应为 1，实际 " + count);
        }
        return match;
    }

    private static void rejectMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                throw new IllegalStateException(node.name + " 已存在 " + name);
            }
        }
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode,
                                             String owner, String name, String descriptor) {
        MethodInsnNode match = null;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == opcode && owner.equals(call.owner)
                && name.equals(call.name) && descriptor.equals(call.desc)) {
                match = call;
                count++;
            }
        }
        if (count != 1 || match == null) {
            throw new IllegalStateException(method.name + " 中 " + owner + '.' + name
                + " 调用数量应为 1，实际 " + count);
        }
        return match;
    }

    private static int countCalls(MethodNode method, int opcode, String owner,
                                  String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == opcode && owner.equals(call.owner)
                && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
