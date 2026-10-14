package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reuses decoded neighbor coordinates without changing Phosphor's packed-position semantics. */
final class PhosphorLocalPropagationAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "me/jellysquid/mods/phosphor/mod/world/lighting/LightingEngine";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/lighting/PhosphorLocalPropagationBridge";
    static final String FETCH = "fetchNeighborDataFromCursor";
    static final String ORIGINAL = "ice$originalFetchNeighborData";
    static final String LOCAL_LIGHT = "ice$getLocalCachedLightFor";
    private static final String FETCH_DESC = "(Lnet/minecraft/world/EnumSkyBlock;)V";
    private static final String POS = "net/minecraft/util/math/BlockPos$MutableBlockPos";
    private static final String LIGHT_DESC = "(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/EnumSkyBlock;)I";

    public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original); ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException(node.name);
        MethodNode fetch = find(node, FETCH, FETCH_DESC);
        MethodNode old = copy(fetch, ORIGINAL, FETCH_DESC); old.access |= Opcodes.ACC_SYNTHETIC; node.methods.add(old);
        MethodNode light = copy(find(node, "getCachedLightFor", LIGHT_DESC), LOCAL_LIGHT,
            LIGHT_DESC.replace(";)I", ";III)I"));
        light.access |= Opcodes.ACC_SYNTHETIC;
        // Original locals 4,5,6 become x&15,y,z&15 arguments; leave every virtual light read intact.
        AbstractInsnNode storageStart = null;
        for (AbstractInsnNode in : light.instructions.toArray()) if (in instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) in;
            if (field.getOpcode() == Opcodes.GETSTATIC && field.owner.equals("net/minecraft/world/chunk/Chunk")
                && field.name.equals("field_186036_a")) { storageStart = previous(in); break; }
        }
        if (storageStart == null || !(storageStart instanceof VarInsnNode) || ((VarInsnNode)storageStart).var != 1)
            throw new IllegalStateException("Phosphor cached light coordinate prelude changed");
        while (light.instructions.getFirst() != storageStart) light.instructions.remove(light.instructions.getFirst());
        light.localVariables = null; node.methods.add(light);

        int x = fetch.maxLocals, y = x + 1, z = x + 2;
        fetch.maxLocals += 3;
        InsnList entry = new InsnList(); LabelNode optimized = new LabelNode();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
        entry.add(new JumpInsnNode(Opcodes.IFNE, optimized));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0)); entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, ORIGINAL, FETCH_DESC, false));
        entry.add(new InsnNode(Opcodes.RETURN)); entry.add(optimized);
        // Keep the original isNeighborDataValid early return before the module lookup.
        AbstractInsnNode validWrite = null;
        for (AbstractInsnNode in : fetch.instructions.toArray()) if (in instanceof FieldInsnNode
            && in.getOpcode() == Opcodes.PUTFIELD && ((FieldInsnNode)in).name.equals("isNeighborDataValid")) { validWrite = in; break; }
        if (validWrite == null) throw new IllegalStateException("Phosphor neighbor validity flow changed");
        // The existing cached-neighbor early return avoids another module lookup on repeated reads.
        fetch.instructions.insertBefore(previous(previous(validWrite)), entry);
        int decoded = 0, indexed = 0, lights = 0;
        for (AbstractInsnNode in : fetch.instructions.toArray()) if (in instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode)in;
            if (call.owner.equals(TARGET) && call.name.equals("decodeWorldCoord")) {
                AbstractInsnNode packedLoad = previous(call);
                if (!(packedLoad instanceof VarInsnNode) || packedLoad.getOpcode() != Opcodes.LLOAD || ((VarInsnNode)packedLoad).var != 4)
                    throw new IllegalStateException("Phosphor neighbor packed-position local changed");
                // A queued y=256 decodes the cursor to y=0, but its DOWN
                // neighbor has a valid packed y=255. Cursor + direction would
                // produce -1 instead. The packed neighbor remains authoritative,
                // including its carries and the existing invalid-height check.
                InsnList values = new InsnList();
                coordinate(values, "func_177958_n", x);
                coordinate(values, "func_177956_o", y);
                coordinate(values, "func_177952_p", z);
                // Leave the returned position on the stack for the original ASTORE.
                fetch.instructions.insert(call, values);
                decoded++;
            } else if (call.owner.equals(POS) && call.name.equals("func_177956_o") && call.desc.equals("()I")
                && previous(call) instanceof VarInsnNode && ((VarInsnNode)previous(call)).var == 6) {
                fetch.instructions.remove(previous(call)); fetch.instructions.set(call, new VarInsnNode(Opcodes.ILOAD, y)); indexed++;
            } else if (call.owner.equals(TARGET) && call.name.equals("getCachedLightFor")) {
                InsnList values = new InsnList(); local(values, x); values.add(new VarInsnNode(Opcodes.ILOAD, y)); local(values, z);
                fetch.instructions.insertBefore(call, values); call.name = LOCAL_LIGHT; call.desc = light.desc; lights++;
            }
        }
        if (decoded != 1 || indexed != 1 || lights != 1) throw new IllegalStateException("Phosphor neighbor call graph changed");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        node.accept(writer); return writer.toByteArray();
    }
    private static void coordinate(InsnList code, String getter, int slot) {
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, POS, getter, "()I", false)); code.add(new VarInsnNode(Opcodes.ISTORE, slot));
    }
    private static void local(InsnList code, int slot) { code.add(new VarInsnNode(Opcodes.ILOAD, slot)); code.add(new IntInsnNode(Opcodes.BIPUSH, 15)); code.add(new InsnNode(Opcodes.IAND)); }
    private static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
        throw new IllegalStateException(node.name + "." + name);
    }
    private static MethodNode copy(MethodNode source, String name, String desc) {
        MethodNode result = new MethodNode(Opcodes.ASM5, source.access, name, desc, null, null); source.accept(result);
        result.name = name; result.desc = desc; return result;
    }
    private static AbstractInsnNode previous(AbstractInsnNode node) { do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node; }
}
