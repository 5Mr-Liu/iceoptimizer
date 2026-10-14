package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Reuses one list traversal while retaining the handler's original control flow and map writes. */
final class SrpMobCapAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "srpmixins/handlers/WorldMobCapHandler";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/srp/SrpMobCapBridge";
    private static final String CONFIG = "srpmixins/config/SRPMixinsConfigHandler";
    private static final String FOLDERS = "srpmixins/config/folders/";
    private static final String WORLD = "net/minecraft/world/World";
    private static final String EVENT = "net/minecraftforge/fml/common/gameevent/TickEvent$WorldTickEvent";
    private static final String CATEGORIES = "ice$mobCapCategories";

    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode tick = null;
        MethodNode clinit = null;
        for (MethodNode method : node.methods) {
            if ("onWorldServerTick".equals(method.name) && ("(L" + EVENT + ";)V").equals(method.desc)) tick = method;
            if ("<clinit>".equals(method.name)) clinit = method;
        }
        if (tick == null || clinit == null) throw new IllegalStateException("SRP mob-cap handler changed");
        List<MethodInsnNode> calls = new ArrayList<MethodInsnNode>();
        List<Type> categories = new ArrayList<Type>();
        AbstractInsnNode dimension = null;
        for (AbstractInsnNode instruction : tick.instructions.toArray()) {
            if (instruction instanceof VarInsnNode && instruction.getOpcode() == Opcodes.ISTORE
                && ((VarInsnNode) instruction).var == 1) dimension = instruction;
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (WORLD.equals(call.owner) && "func_72907_a".equals(call.name)
                && "(Ljava/lang/Class;)I".equals(call.desc)) {
                AbstractInsnNode previous = call.getPrevious();
                while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                if (!(previous instanceof LdcInsnNode) || !(((LdcInsnNode) previous).cst instanceof Type))
                    throw new IllegalStateException("SRP count category is no longer constant");
                categories.add((Type) ((LdcInsnNode) previous).cst);
                calls.add(call);
            }
        }
        if (calls.size() != 7 || dimension == null) throw new IllegalStateException("SRP mob-cap count graph changed");
        String[] expected = { "EntityPStationaryArchitect", "EntityInfSquid", "EntityLum", "EntityAta",
            "EntityParasiticScent", "EntityInfEnderman", "EntityInfEndermanHead" };
        for (int i = 0; i < expected.length; i++) {
            if (!categories.get(i).getInternalName().endsWith("/" + expected[i]))
                throw new IllegalStateException("SRP count category order changed");
        }
        int invocation = tick.maxLocals++;
        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new FieldInsnNode(Opcodes.GETFIELD, EVENT, "world", "L" + WORLD + ";"));
        start.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, CATEGORIES, "Ljava/lang/Object;"));
        start.add(new VarInsnNode(Opcodes.ILOAD, 1));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "ice$mobCapMask", "(I)I", false));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(L" + WORLD + ";Ljava/lang/Object;I)Ljava/lang/Object;", false));
        start.add(new VarInsnNode(Opcodes.ASTORE, invocation));
        tick.instructions.insert(dimension, start);
        for (int index = 0; index < calls.size(); index++) {
            MethodInsnNode call = calls.get(index);
            tick.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, invocation));
            tick.instructions.insertBefore(call, new IntInsnNode(Opcodes.BIPUSH, index));
            tick.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "count",
                "(L" + WORLD + ";Ljava/lang/Class;Ljava/lang/Object;I)I", false));
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            CATEGORIES, "Ljava/lang/Object;", null, null));
        InsnList init = new InsnList();
        init.add(new IntInsnNode(Opcodes.BIPUSH, categories.size()));
        init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        for (int i = 0; i < categories.size(); i++) {
            init.add(new InsnNode(Opcodes.DUP));
            init.add(new IntInsnNode(Opcodes.BIPUSH, i));
            init.add(new LdcInsnNode(categories.get(i)));
            init.add(new InsnNode(Opcodes.AASTORE));
        }
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "compileCategories",
            "([Ljava/lang/Class;)Ljava/lang/Object;", false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, CATEGORIES, "Ljava/lang/Object;"));
        clinit.instructions.insert(init);
        node.methods.add(mask());
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode mask() {
        MethodNode method = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "ice$mobCapMask", "(I)I", null, null);
        InsnList code = method.instructions;
        code.add(new IntInsnNode(Opcodes.BIPUSH, 24)); // gnat and scent are unconditional
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        addOption(code, "deterrents", "DeterrentConfig", "nexusCap", 1);
        addOption(code, "waterparas", "WaterParaConfig", "waterParasiteCap", 6);
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, done));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, CONFIG, "simmermen", "L" + FOLDERS + "SimmermanConfig;"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, FOLDERS + "SimmermanConfig", "endSimmermenCap", "I"));
        code.add(new JumpInsnNode(Opcodes.IFLE, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 96));
        code.add(new InsnNode(Opcodes.IOR));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private static void addOption(InsnList code, String field, String type, String option, int bits) {
        LabelNode skip = new LabelNode();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, CONFIG, field, "L" + FOLDERS + type + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, FOLDERS + type, option, "I"));
        code.add(new JumpInsnNode(Opcodes.IFLT, skip));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new IntInsnNode(Opcodes.BIPUSH, bits));
        code.add(new InsnNode(Opcodes.IOR));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(skip);
    }
}
