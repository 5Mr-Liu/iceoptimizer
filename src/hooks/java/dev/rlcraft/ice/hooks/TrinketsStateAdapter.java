package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Keeps Trinkets' full live writer and independently owned mutable output trees. */
final class TrinketsStateAdapter implements OptimizerBytecodeAdapter {
    static final String PROPERTIES = "xzeroair/trinkets/capabilities/Trinket/TrinketProperties";
    static final String ELEMENTS = "xzeroair/trinkets/capabilities/race/ElementalAttributes";
    static final String COUNTER = "xzeroair/trinkets/util/handlers/Counter";
    static final String TICKS = "xzeroair/trinkets/util/handlers/TickHandler";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/trinkets/TrinketsStateBridge";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/trinkets/TrinketsStateAccess";
    static final String CACHE = "L" + BRIDGE + "$ScalarCache;";
    private static final String FIELD = "ice$trinketsScalarCache";
    private static final String NBT = "net/minecraft/nbt/NBTTagCompound";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!PROPERTIES.equals(node.name) && !ELEMENTS.equals(node.name)
            && !COUNTER.equals(node.name) && !TICKS.equals(node.name)) {
            throw new IllegalStateException("Trinkets state target changed: " + node.name);
        }
        MethodNode changedWriter = null;
        if (TICKS.equals(node.name)) {
            changedWriter = writer(node, "saveCountersToNBT", "V");
            patchCounters(changedWriter);
        }
        else {
            addCacheAccess(node);
            if (PROPERTIES.equals(node.name)) {
                changedWriter = writer(node, "saveToNBT", "L" + NBT + ";");
                patchOwnerWriter(changedWriter,
                    new String[] {"variant", "slot", "handler", "exp", "mana", "main.ability",
                        "alt.ability", "crafter.name", "crafter.uuid"});
            } else if (ELEMENTS.equals(node.name)) {
                changedWriter = writer(node, "saveToNBT", "L" + NBT + ";");
                patchOwnerWriter(changedWriter,
                    new String[] {"primary", "secondary"});
            }
        }
        if (changedWriter != null) recomputeWriterFrames(node, changedWriter);
        // Preserve every untouched method's original frame types. Resolving the
        // hierarchy of unrelated gameplay methods during early loading is unsafe.
        ClassWriter output = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(output);
        return output.toByteArray();
    }

    private static void recomputeWriterFrames(ClassNode owner, MethodNode method) {
        // Only the reviewed writer receives extra local variables. Its control
        // flow joins standard NBT values, counters and exception handlers.
        ClassWriter frames = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        frames.visit(owner.version, owner.access, owner.name, owner.signature, owner.superName,
            owner.interfaces.toArray(new String[owner.interfaces.size()]));
        method.accept(frames);
        frames.visitEnd();
        ClassNode calculated = new ClassNode(Opcodes.ASM5);
        new ClassReader(frames.toByteArray()).accept(calculated, ClassReader.EXPAND_FRAMES);
        owner.methods.set(owner.methods.indexOf(method), calculated.methods.get(0));
    }

    private static MethodNode writer(ClassNode owner, String name, String result) {
        for (MethodNode method : owner.methods) if (name.equals(method.name)
            && ("(L" + NBT + ";)" + result).equals(method.desc)) return method;
        throw new IllegalStateException("Trinkets state writer missing: " + owner.name + "." + name);
    }

    private static void addCacheAccess(ClassNode node) {
        if (node.interfaces.contains(ACCESS)) throw new IllegalStateException("Trinkets state cache already installed");
        node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            FIELD, "Ljava/lang/Object;", null, null));
        MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FIELD, "()Ljava/lang/Object;", null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, "Ljava/lang/Object;"));
        get.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(get);
        MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            FIELD, "(Ljava/lang/Object;)V", null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FIELD, "Ljava/lang/Object;"));
        set.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(set);
    }

    private static void patchOwnerWriter(MethodNode method, String[] keys) {
        int cacheLocal = method.maxLocals++;
        method.instructions.insert(cache(0, cacheLocal, keys.length));
        rewriteSetters(method, keys, cacheLocal);
    }

    private static void patchCounters(MethodNode method) {
        int counterLocal = method.maxLocals++;
        int cacheLocal = method.maxLocals++;
        MethodInsnNode save = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (COUNTER.equals(call.owner) && "saveToNBT".equals(call.name) && "()Z".equals(call.desc)) {
                if (save != null) throw new IllegalStateException("Trinkets counter writer has multiple save checks");
                save = call;
            }
        }
        if (save == null) throw new IllegalStateException("Trinkets counter save check missing");
        // Reuse the last original Entry.getValue result. Calling getValue again can
        // observe another counter when a custom entry has dynamic behavior.
        InsnList capture = new InsnList();
        capture.add(new InsnNode(Opcodes.DUP));
        capture.add(new VarInsnNode(Opcodes.ASTORE, counterLocal));
        method.instructions.insertBefore(save, capture);
        AbstractInsnNode allocation = save;
        do { allocation = allocation.getNext(); }
        while (allocation != null && !(allocation instanceof TypeInsnNode
            && allocation.getOpcode() == Opcodes.NEW && NBT.equals(((TypeInsnNode) allocation).desc)));
        if (allocation == null) throw new IllegalStateException("Trinkets saved counter tag allocation missing");
        // This point is reached only after the original saveNBT branch accepted it.
        method.instructions.insertBefore(allocation, cache(counterLocal, cacheLocal, 4));
        rewriteSetters(method, new String[] {"Tick", "Length", "Countdown", "ShouldTick"}, cacheLocal);
    }

    private static InsnList cache(int ownerLocal, int resultLocal, int slots) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        code.add(new IntInsnNode(Opcodes.BIPUSH, slots));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "cacheForWrite",
            "(Ljava/lang/Object;I)" + CACHE, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        return code;
    }

    private static void rewriteSetters(MethodNode method, String[] keys, int cacheLocal) {
        int changed = 0;
        String key = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof LdcInsnNode && ((LdcInsnNode) instruction).cst instanceof String) {
                key = (String) ((LdcInsnNode) instruction).cst;
            }
            if (!(instruction instanceof MethodInsnNode) || changed == keys.length) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!NBT.equals(call.owner) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            String replacement = scalarSetter(call.name, call.desc);
            if (replacement == null) continue;
            if (!keys[changed].equals(key)) {
                throw new IllegalStateException("Trinkets scalar schema changed: expected " + keys[changed] + ", got " + key);
            }
            InsnList arguments = new InsnList();
            arguments.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
            arguments.add(new IntInsnNode(Opcodes.BIPUSH, changed));
            method.instructions.insertBefore(call, arguments);
            int end = call.desc.indexOf(')');
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, replacement,
                "(L" + NBT + ";" + call.desc.substring(1, end) + CACHE + "I)V", false));
            changed++;
        }
        if (changed != keys.length) throw new IllegalStateException("Trinkets scalar write count changed: " + changed);
    }

    private static String scalarSetter(String name, String descriptor) {
        if (("func_74768_a".equals(name) || "setInteger".equals(name)) && "(Ljava/lang/String;I)V".equals(descriptor)) return "setInteger";
        if (("func_74776_a".equals(name) || "setFloat".equals(name)) && "(Ljava/lang/String;F)V".equals(descriptor)) return "setFloat";
        if (("func_74757_a".equals(name) || "setBoolean".equals(name)) && "(Ljava/lang/String;Z)V".equals(descriptor)) return "setBoolean";
        if (("func_74778_a".equals(name) || "setString".equals(name)) && "(Ljava/lang/String;Ljava/lang/String;)V".equals(descriptor)) return "setString";
        return null;
    }
}
