package dev.rlcraft.ice.hooks;

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

/** Preserve the region walk and serialization, allocating only for occupied cells/new groups. */
final class OtgStructureSaveAllocationAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/pg85/otg/customobjects/structures/CustomStructureFileManager";
    static final String ORIGINAL = "ice$saveStructureDataOriginal";
    private static final String DESCRIPTOR = "(Ljava/util/Map;Lcom/pg85/otg/common/LocalWorld;)V";

    @Override public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name) || !transformedName.replace('.', '/').equals(node.name)) {
            throw new IllegalStateException("OTG CustomStructureFileManager class changed");
        }
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (ORIGINAL.equals(candidate.name)) throw new IllegalStateException("OTG save optimization already installed");
            if ("saveStructureData".equals(candidate.name) && DESCRIPTOR.equals(candidate.desc)) method = candidate;
        }
        if (method == null) throw new IllegalStateException("OTG saveStructureData missing");
        MethodNode original = new MethodNode(Opcodes.ASM5, method.access, method.name, method.desc,
            method.signature, method.exceptions.toArray(new String[method.exceptions.size()]));
        method.accept(original);
        original.name = ORIGINAL;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(original);
        moveCoordinateCreation(method);
        moveUnusedListCreation(method);

        LabelNode optimized = new LabelNode();
        InsnList prefix = new InsnList();
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OtgStructureCompressionAdapter.BRIDGE,
            "isEnabled", "()Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFNE, optimized));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, ORIGINAL, DESCRIPTOR, false));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(optimized);
        method.instructions.insert(prefix);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
        };
        return OtgAsmMethodWriter.write(reader, writer, method, original);
    }

    private static void moveCoordinateCreation(MethodNode method) {
        MethodInsnNode coordinates = uniqueCall(method, "com/pg85/otg/util/ChunkCoordinate", "fromChunkCoords");
        AbstractInsnNode end = nextCode(coordinates);
        requireVariable(end, Opcodes.ASTORE, 9);
        AbstractInsnNode start = coordinates;
        int keyCalls = 0;
        while (start != null && keyCalls < 2) {
            start = start.getPrevious();
            if (start instanceof MethodInsnNode && "java/util/Map$Entry".equals(((MethodInsnNode) start).owner)
                && "getKey".equals(((MethodInsnNode) start).name)) keyCalls++;
        }
        if (keyCalls != 2) throw new IllegalStateException("OTG coordinate calculation changed");
        start = previousCode(start);
        requireVariable(start, Opcodes.ALOAD, 5);
        MethodInsnNode lookup = uniqueCall(method, "com/pg85/otg/customobjects/structures/StructureDataRegion", "getStructure");
        AbstractInsnNode structure = nextCode(lookup);
        requireVariable(structure, Opcodes.ASTORE, 10);
        AbstractInsnNode load = nextCode(structure);
        requireVariable(load, Opcodes.ALOAD, 10);
        AbstractInsnNode branch = nextCode(load);
        if (!(branch instanceof JumpInsnNode) || branch.getOpcode() != Opcodes.IFNULL) {
            throw new IllegalStateException("OTG empty cell branch changed");
        }
        method.instructions.insert(branch, removeBlock(method, start, end));
    }

    private static void moveUnusedListCreation(MethodNode method) {
        TypeInsnNode first = null;
        int lists = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                && "java/util/ArrayList".equals(((TypeInsnNode) instruction).desc)) {
                if (first == null) first = (TypeInsnNode) instruction;
                lists++;
            }
        }
        if (lists != 2) throw new IllegalStateException("OTG grouped list allocations changed");
        AbstractInsnNode end = nextCode(nextCode(nextCode(first)));
        requireVariable(end, Opcodes.ASTORE, 13);
        AbstractInsnNode map = nextCode(end);
        requireVariable(map, Opcodes.ALOAD, 12);
        AbstractInsnNode branch = nextCode(map);
        if (!(branch instanceof JumpInsnNode) || branch.getOpcode() != Opcodes.IFNONNULL) {
            throw new IllegalStateException("OTG existing group branch changed");
        }
        method.instructions.insert(branch, removeBlock(method, first, end));
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && owner.equals(((MethodInsnNode) instruction).owner)
                && name.equals(((MethodInsnNode) instruction).name)) {
                if (found != null) throw new IllegalStateException("OTG duplicate " + name);
                found = (MethodInsnNode) instruction;
            }
        }
        if (found == null) throw new IllegalStateException("OTG missing " + name);
        return found;
    }
    private static InsnList removeBlock(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
        InsnList block = new InsnList();
        AbstractInsnNode instruction = first;
        while (instruction != null) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            block.add(instruction);
            if (instruction == last) return block;
            instruction = next;
        }
        throw new IllegalStateException("OTG allocation block end missing");
    }
    private static void requireVariable(AbstractInsnNode instruction, int opcode, int variable) {
        if (!(instruction instanceof VarInsnNode) || instruction.getOpcode() != opcode
            || ((VarInsnNode) instruction).var != variable) throw new IllegalStateException("OTG allocation locals changed");
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
