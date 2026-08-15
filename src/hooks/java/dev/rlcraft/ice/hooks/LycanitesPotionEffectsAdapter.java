package dev.rlcraft.ice.hooks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Gives the 35 reviewed constant effect lookups stable atomic slots and reuses
 * the captured-class nearby-entity predicate without changing event logic.
 */
final class LycanitesPotionEffectsAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/lycanitesmobs/PotionEffects";
    static final String OBJECT_MANAGER = "com/lycanitesmobs/ObjectManager";
    static final String POTION = "com/lycanitesmobs/PotionBase";
    static final String GET_EFFECT_DESCRIPTOR = "(Ljava/lang/String;)L" + POTION + ";";
    static final String HELPER = "rlcraftIce$getEffect";
    static final String HELPER_DESCRIPTOR = "(I)L" + POTION + ";";
    static final String SLOT_FIELD = "rlcraftIce$effectSlots";
    static final String NAMES_FIELD = "rlcraftIce$effectNames";
    static final String NULL_FIELD = "rlcraftIce$nullEffect";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/lycanites/LycanitesEffectBridge";
    private static final int EXPECTED_CALLS = 35;
    private static final Set<String> EXPECTED_NAMES = new HashSet<String>(Arrays.asList(
        "aphagia", "bleed", "cleansed", "decay", "fallresist", "fear", "immunization",
        "insomnia", "instability", "leech", "lifeleak", "paralysis", "penetration",
        "plague", "rejuvenation", "repulsion", "smited", "smouldering",
        "swiftswimming", "weight"));

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Lycanites PotionEffects 类名变化：" + node.name);
        rejectField(node, SLOT_FIELD);
        rejectField(node, NAMES_FIELD);
        rejectField(node, NULL_FIELD);
        for (MethodNode method : node.methods) {
            if (HELPER.equals(method.name)) throw new IllegalStateException("Lycanites PotionEffects 已存在 ICE helper");
        }

        Map<String, Integer> slots = new LinkedHashMap<String, Integer>();
        int calls = 0;
        int predicates = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.getOpcode() == Opcodes.INVOKESTATIC && OBJECT_MANAGER.equals(call.owner)
                        && "getEffect".equals(call.name) && GET_EFFECT_DESCRIPTOR.equals(call.desc)) {
                        AbstractInsnNode previous = previousOpcode(call);
                        if (!(previous instanceof LdcInsnNode)
                            || !(((LdcInsnNode) previous).cst instanceof String)) {
                            throw new IllegalStateException("Lycanites PotionEffects 非常量 getEffect 调用");
                        }
                        String name = (String) ((LdcInsnNode) previous).cst;
                        Integer slot = slots.get(name);
                        if (slot == null) {
                            slot = Integer.valueOf(slots.size());
                            slots.put(name, slot);
                        }
                        method.instructions.set(previous, new LdcInsnNode(slot));
                        call.owner = TARGET;
                        call.name = HELPER;
                        call.desc = HELPER_DESCRIPTOR;
                        call.itf = false;
                        calls++;
                    }
                } else if (instruction instanceof InvokeDynamicInsnNode
                    && "getNearbyEntities".equals(method.name)) {
                    InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                    if ("(Ljava/lang/Class;)Lcom/google/common/base/Predicate;".equals(dynamic.desc)) {
                        method.instructions.set(dynamic, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            BRIDGE, "nearbyPredicate", dynamic.desc, false));
                        predicates++;
                    }
                }
            }
        }
        if (calls != EXPECTED_CALLS || slots.size() != EXPECTED_NAMES.size()
            || !EXPECTED_NAMES.equals(slots.keySet()) || predicates != 1) {
            throw new IllegalStateException("Lycanites PotionEffects 调用图变化：calls=" + calls
                + '/' + EXPECTED_CALLS + ", slots=" + slots.keySet() + ", predicates=" + predicates);
        }

        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNTHETIC, SLOT_FIELD, "Ljava/util/concurrent/atomic/AtomicReferenceArray;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNTHETIC, NAMES_FIELD, "[Ljava/lang/String;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNTHETIC, NULL_FIELD, "Ljava/lang/Object;", null, null));
        initializeFields(node, slots);
        addHelper(node);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void initializeFields(ClassNode node, Map<String, Integer> slots) {
        MethodNode clinit = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                clinit = method;
                matches++;
            }
        }
        if (matches != 1 || clinit == null) {
            throw new IllegalStateException("Lycanites PotionEffects <clinit> 数量变化：" + matches);
        }
        AbstractInsnNode returnInstruction = null;
        int returns = 0;
        for (AbstractInsnNode instruction : clinit.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                returnInstruction = instruction;
                returns++;
            }
        }
        if (returns != 1 || returnInstruction == null) {
            throw new IllegalStateException("Lycanites PotionEffects <clinit> return 数量变化：" + returns);
        }
        InsnList init = new InsnList();
        init.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,
            "java/util/concurrent/atomic/AtomicReferenceArray"));
        init.add(new InsnNode(Opcodes.DUP));
        pushInt(init, slots.size());
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/util/concurrent/atomic/AtomicReferenceArray", "<init>", "(I)V", false));
        init.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, TARGET, SLOT_FIELD,
            "Ljava/util/concurrent/atomic/AtomicReferenceArray;"));
        pushInt(init, slots.size());
        init.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            init.add(new InsnNode(Opcodes.DUP));
            pushInt(init, entry.getValue().intValue());
            init.add(new LdcInsnNode(entry.getKey()));
            init.add(new InsnNode(Opcodes.AASTORE));
        }
        init.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, TARGET, NAMES_FIELD,
            "[Ljava/lang/String;"));
        init.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, TARGET, NULL_FIELD,
            "Ljava/lang/Object;"));
        clinit.instructions.insertBefore(returnInstruction, init);
    }

    private static void addHelper(ClassNode node) {
        MethodNode helper = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            HELPER, HELPER_DESCRIPTOR, null, null);
        MethodVisitor code = helper;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "useEffectCache", "()Z", false);
        Label cachedPath = new Label();
        code.visitJumpInsn(Opcodes.IFNE, cachedPath);
        loadName(code);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, OBJECT_MANAGER, "getEffect", GET_EFFECT_DESCRIPTOR, false);
        code.visitInsn(Opcodes.ARETURN);

        code.visitLabel(cachedPath);
        code.visitFieldInsn(Opcodes.GETSTATIC, TARGET, SLOT_FIELD,
            "Ljava/util/concurrent/atomic/AtomicReferenceArray;");
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicReferenceArray", "get", "(I)Ljava/lang/Object;", false);
        code.visitVarInsn(Opcodes.ASTORE, 1);
        Label resolve = new Label();
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitJumpInsn(Opcodes.IFNULL, resolve);
        returnDecoded(code, 1);

        code.visitLabel(resolve);
        loadName(code);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, OBJECT_MANAGER, "getEffect", GET_EFFECT_DESCRIPTOR, false);
        code.visitVarInsn(Opcodes.ASTORE, 2);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        Label nonNull = new Label();
        Label encoded = new Label();
        code.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        code.visitFieldInsn(Opcodes.GETSTATIC, TARGET, NULL_FIELD, "Ljava/lang/Object;");
        code.visitJumpInsn(Opcodes.GOTO, encoded);
        code.visitLabel(nonNull);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitLabel(encoded);
        code.visitVarInsn(Opcodes.ASTORE, 3);
        code.visitFieldInsn(Opcodes.GETSTATIC, TARGET, SLOT_FIELD,
            "Ljava/util/concurrent/atomic/AtomicReferenceArray;");
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitInsn(Opcodes.ACONST_NULL);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicReferenceArray", "compareAndSet",
            "(ILjava/lang/Object;Ljava/lang/Object;)Z", false);
        Label won = new Label();
        code.visitJumpInsn(Opcodes.IFNE, won);
        code.visitFieldInsn(Opcodes.GETSTATIC, TARGET, SLOT_FIELD,
            "Ljava/util/concurrent/atomic/AtomicReferenceArray;");
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicReferenceArray", "get", "(I)Ljava/lang/Object;", false);
        code.visitVarInsn(Opcodes.ASTORE, 3);
        code.visitLabel(won);
        returnDecoded(code, 3);
        code.visitMaxs(0, 0);
        code.visitEnd();
        node.methods.add(helper);
    }

    private static void loadName(MethodVisitor code) {
        code.visitFieldInsn(Opcodes.GETSTATIC, TARGET, NAMES_FIELD, "[Ljava/lang/String;");
        code.visitVarInsn(Opcodes.ILOAD, 0);
        code.visitInsn(Opcodes.AALOAD);
    }

    private static void returnDecoded(MethodVisitor code, int local) {
        code.visitVarInsn(Opcodes.ALOAD, local);
        code.visitFieldInsn(Opcodes.GETSTATIC, TARGET, NULL_FIELD, "Ljava/lang/Object;");
        Label value = new Label();
        code.visitJumpInsn(Opcodes.IF_ACMPNE, value);
        code.visitInsn(Opcodes.ACONST_NULL);
        code.visitInsn(Opcodes.ARETURN);
        code.visitLabel(value);
        code.visitVarInsn(Opcodes.ALOAD, local);
        code.visitTypeInsn(Opcodes.CHECKCAST, POTION);
        code.visitInsn(Opcodes.ARETURN);
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void pushInt(InsnList list, int value) {
        if (value >= -1 && value <= 5) list.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value <= Byte.MAX_VALUE) list.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value));
        else list.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value));
    }

    private static void rejectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name)) throw new IllegalStateException("Lycanites PotionEffects 字段冲突：" + name);
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
    }
}
