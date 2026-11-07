package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Retains RC's custom-entry serialization and all of RLTweaker's cleanup/readback/locking. */
final class RecurrentCleanupAdapter implements OptimizerBytecodeAdapter {
    static final String DATA = "ivorius/reccomplex/world/gen/feature/WorldStructureGenerationData";
    static final String CLEANUP = "com/charles445/rltweaker/handler/RecurrentHandler$RCCleanup";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/recurrent/RecurrentCleanupAccess";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/recurrent/RecurrentCleanupBridge";
    private static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    private static final String WRITE_DESC = "(L" + NBT + ";)L" + NBT + ";";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5); reader.accept(node, 0);
        if (DATA.equals(node.name)) installWriter(node);
        else if (CLEANUP.equals(node.name)) redirectCleanup(node);
        else throw new IllegalStateException("Unknown RC cleanup target " + node.name);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }

    private static void installWriter(ClassNode node) {
        require(!node.interfaces.contains(ACCESS), "already transformed");
        MethodNode source = null;
        for (MethodNode method : node.methods) {
            require(!"serializeNBT".equals(method.name), "custom serializeNBT override");
            if (("func_189551_b".equals(method.name) || "writeToNBT".equals(method.name))
                    && WRITE_DESC.equals(method.desc)) source = method;
        }
        require(source != null && source.tryCatchBlocks.isEmpty(), "writer missing or has handlers");
        List<AbstractInsnNode> ops = executable(source);
        Cursor cursor = new Cursor(ops);
        objects(node, cursor, "entries", "$StructureEntry");
        int customStart = cursor.index;
        objects(node, cursor, "customEntries", "$CustomEntry");
        int customEnd = cursor.index;
        chunks(node, cursor, "checkedChunks");
        chunks(node, cursor, "checkedChunksFinal");
        cursor.var(Opcodes.ALOAD, 1); cursor.op(Opcodes.ARETURN);
        require(cursor.index == ops.size(), "extra writer statements");
        MethodNode fast = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$writeCleanupNbt", WRITE_DESC, null, null);
        for (int i = customStart; i < customEnd; i++) fast.instructions.add(ops.get(i).clone(null));
        fast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); fast.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(fast);
        accessor(node, "entryMap", "Ljava/util/Map;", "ice$cleanupEntries");
        accessor(node, "checkedChunks", "Ljava/util/Set;", "ice$cleanupCheckedChunks");
        accessor(node, "checkedChunksFinal", "Ljava/util/Set;", "ice$cleanupCheckedChunksFinal");
        node.interfaces.add(ACCESS);
    }

    private static void objects(ClassNode node, Cursor c, String key, String entrySuffix) {
        c.var(Opcodes.ALOAD, 1); c.constant(key); c.var(Opcodes.ALOAD, 0);
        c.field(DATA, "entryMap", "Ljava/util/Map;");
        c.call(Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;");
        c.call(Opcodes.INVOKEINTERFACE, "java/util/Collection", "stream", "()Ljava/util/stream/Stream;");
        Cursor predicate = new Cursor(executable(lambda(node, c, "test", "()Ljava/util/function/Predicate;")));
        predicate.var(Opcodes.ALOAD, 0);
        AbstractInsnNode cast = predicate.next();
        require(cast.getOpcode() == Opcodes.INSTANCEOF && cast instanceof TypeInsnNode
            && (DATA + entrySuffix).equals(((TypeInsnNode) cast).desc), "entry predicate changed");
        predicate.op(Opcodes.IRETURN); require(predicate.done(), "entry predicate has side effects");
        c.call(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "filter", "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;");
        collect(c, "java/lang/Iterable");
        c.call(Opcodes.INVOKESTATIC, "ivorius/ivtoolkit/tools/NBTCompoundObjects", "writeListTo",
            "(L" + NBT + ";Ljava/lang/String;Ljava/lang/Iterable;)V");
    }

    private static void chunks(ClassNode node, Cursor c, String key) {
        c.var(Opcodes.ALOAD, 1); c.constant(key); c.var(Opcodes.ALOAD, 0);
        c.field(DATA, key, "Ljava/util/Set;");
        c.call(Opcodes.INVOKEINTERFACE, "java/util/Set", "stream", "()Ljava/util/stream/Stream;");
        Cursor mapper = new Cursor(executable(lambda(node, c, "apply", "()Ljava/util/function/Function;")));
        mapper.op(Opcodes.ICONST_2);
        AbstractInsnNode array = mapper.next();
        require(array instanceof IntInsnNode && array.getOpcode() == Opcodes.NEWARRAY
            && ((IntInsnNode) array).operand == Opcodes.T_INT, "chunk array changed");
        for (int i = 0; i < 2; i++) {
            mapper.op(Opcodes.DUP); mapper.op(i == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1);
            mapper.var(Opcodes.ALOAD, 0);
            AbstractInsnNode field = mapper.next();
            require(field instanceof FieldInsnNode && field.getOpcode() == Opcodes.GETFIELD, "chunk coordinate changed");
            FieldInsnNode f = (FieldInsnNode) field;
            require("net/minecraft/util/math/ChunkPos".equals(f.owner) && "I".equals(f.desc)
                && (i == 0 ? "field_77276_a".equals(f.name) || "x".equals(f.name)
                    : "field_77275_b".equals(f.name) || "z".equals(f.name)), "chunk coordinate changed");
            mapper.op(Opcodes.IASTORE);
        }
        mapper.op(Opcodes.ARETURN); require(mapper.done(), "chunk mapper has side effects");
        c.call(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "map", "(Ljava/util/function/Function;)Ljava/util/stream/Stream;");
        collect(c, "java/util/List");
        c.call(Opcodes.INVOKESTATIC, "ivorius/ivtoolkit/tools/NBTTagLists", "writeIntArraysTo",
            "(L" + NBT + ";Ljava/lang/String;Ljava/util/List;)V");
    }

    private static MethodNode lambda(ClassNode node, Cursor c, String name, String desc) {
        AbstractInsnNode instruction = c.next(); require(instruction instanceof InvokeDynamicInsnNode, "lambda missing");
        InvokeDynamicInsnNode lambda = (InvokeDynamicInsnNode) instruction;
        require(name.equals(lambda.name) && desc.equals(lambda.desc)
            && "java/lang/invoke/LambdaMetafactory".equals(lambda.bsm.getOwner())
            && "metafactory".equals(lambda.bsm.getName()) && lambda.bsmArgs.length == 3
            && lambda.bsmArgs[1] instanceof Handle, "lambda factory changed");
        Handle handle = (Handle) lambda.bsmArgs[1];
        require(handle.getTag() == Opcodes.H_INVOKESTATIC && DATA.equals(handle.getOwner()), "lambda target changed");
        for (MethodNode method : node.methods)
            if (method.name.equals(handle.getName()) && method.desc.equals(handle.getDesc())) return method;
        throw new IllegalStateException("RC cleanup lambda body missing");
    }

    private static void collect(Cursor c, String castType) {
        c.call(Opcodes.INVOKESTATIC, "java/util/stream/Collectors", "toList", "()Ljava/util/stream/Collector;");
        c.call(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "collect", "(Ljava/util/stream/Collector;)Ljava/lang/Object;");
        AbstractInsnNode cast = c.next(); require(cast instanceof TypeInsnNode
            && cast.getOpcode() == Opcodes.CHECKCAST && castType.equals(((TypeInsnNode) cast).desc), "collection cast changed");
    }

    private static void accessor(ClassNode node, String field, String desc, String name) {
        boolean found = false;
        for (FieldNode f : node.fields) if (field.equals(f.name) && desc.equals(f.desc)) found = true;
        require(found, "storage field missing");
        MethodNode method = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, "()" + desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, DATA, field, desc));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
    }

    private static void redirectCleanup(ClassNode node) {
        int changed = 0;
        for (MethodNode method : node.methods) if ("wipeRecurrentData".equals(method.name)
                && "(Lnet/minecraft/world/World;)V".equals(method.desc)) {
            List<String> removed = new ArrayList<String>(); MethodInsnNode serialize = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("net/minecraft/world/storage/WorldSavedData".equals(call.owner) && "serializeNBT".equals(call.name)
                        && ("()L" + NBT + ";").equals(call.desc) && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    require(serialize == null, "multiple cleanup serializations"); serialize = call;
                }
                if (NBT.equals(call.owner) && ("removeTag".equals(call.name) || "func_82580_o".equals(call.name))
                        && "(Ljava/lang/String;)V".equals(call.desc)) {
                    AbstractInsnNode previous = call.getPrevious();
                    while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                    require(previous instanceof LdcInsnNode, "nonconstant cleanup key");
                    removed.add(String.valueOf(((LdcInsnNode) previous).cst));
                }
            }
            require(serialize != null && removed.equals(java.util.Arrays.asList("entries", "checkedChunks", "checkedChunksFinal")),
                "cleanup removal contract changed");
            List<AbstractInsnNode> ops = executable(method);
            Cursor contract = new Cursor(ops);
            contract.index = ops.indexOf(serialize) - 1;
            require(contract.index >= 0, "cleanup receiver missing");
            contract.var(Opcodes.ALOAD, 4); contract.next(); contract.var(Opcodes.ASTORE, 5);
            for (String key : removed) {
                contract.var(Opcodes.ALOAD, 5); contract.constant(key);
                AbstractInsnNode next = contract.next();
                require(next instanceof MethodInsnNode, "cleanup tag observed before removal");
                MethodInsnNode remove = (MethodInsnNode) next;
                require(remove.getOpcode() == Opcodes.INVOKEVIRTUAL && NBT.equals(remove.owner)
                    && ("removeTag".equals(remove.name) || "func_82580_o".equals(remove.name))
                    && "(Ljava/lang/String;)V".equals(remove.desc), "cleanup removal changed");
            }
            contract.var(Opcodes.ALOAD, 4); contract.var(Opcodes.ALOAD, 5);
            AbstractInsnNode read = contract.next();
            require(read instanceof MethodInsnNode && read.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "net/minecraft/world/storage/WorldSavedData".equals(((MethodInsnNode) read).owner)
                && ("readFromNBT".equals(((MethodInsnNode) read).name) || "func_76184_a".equals(((MethodInsnNode) read).name))
                && ("(L" + NBT + ";)V").equals(((MethodInsnNode) read).desc), "cleanup readback changed");
            serialize.setOpcode(Opcodes.INVOKESTATIC); serialize.owner = BRIDGE;
            serialize.name = "serializeForCleanup";
            serialize.desc = "(Lnet/minecraft/world/storage/WorldSavedData;)L" + NBT + ";";
            changed++;
        }
        require(changed == 1, "cleanup method missing");
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode n : method.instructions.toArray()) if (n.getOpcode() >= 0) result.add(n);
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("RC cleanup: " + message);
    }
    private static final class Cursor {
        final List<AbstractInsnNode> ops; int index;
        Cursor(List<AbstractInsnNode> ops) { this.ops = ops; }
        boolean done() { return index == ops.size(); }
        AbstractInsnNode next() { require(index < ops.size(), "truncated writer"); return ops.get(index++); }
        void op(int opcode) { require(next().getOpcode() == opcode, "unexpected instruction"); }
        void var(int opcode, int slot) { AbstractInsnNode n = next(); require(n instanceof VarInsnNode
            && n.getOpcode() == opcode && ((VarInsnNode) n).var == slot, "unexpected local"); }
        void constant(String value) { AbstractInsnNode n = next(); require(n instanceof LdcInsnNode
            && value.equals(((LdcInsnNode) n).cst), "unexpected NBT key"); }
        void field(String owner, String name, String desc) { AbstractInsnNode n = next(); require(n instanceof FieldInsnNode
            && n.getOpcode() == Opcodes.GETFIELD && owner.equals(((FieldInsnNode) n).owner)
            && name.equals(((FieldInsnNode) n).name) && desc.equals(((FieldInsnNode) n).desc), "unexpected field"); }
        void call(int opcode, String owner, String name, String desc) { AbstractInsnNode n = next(); require(n instanceof MethodInsnNode
            && n.getOpcode() == opcode && owner.equals(((MethodInsnNode) n).owner)
            && name.equals(((MethodInsnNode) n).name) && desc.equals(((MethodInsnNode) n).desc), "unexpected call"); }
    }
}
