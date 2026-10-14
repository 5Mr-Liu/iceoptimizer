package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Holds arrays across the reviewed Biped/Quadruped part sequence after all callbacks. */
final class CommonModelStateBatchAdapter implements OptimizerBytecodeAdapter {
    static final String BIPED = "net/minecraft/client/model/ModelBiped";
    static final String QUADRUPED = "net/minecraft/client/model/ModelQuadruped";
    static final String RENDER = "func_78088_a";
    static final String RENDER_DESCRIPTOR = "(Lnet/minecraft/entity/Entity;FFFFFF)V";
    static final String BRIDGE = ModelRendererVboAdapter.STATE_BATCH_BRIDGE;

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        boolean biped = BIPED.equals(node.name);
        if (!biped && !QUADRUPED.equals(node.name)) {
            throw new IllegalStateException("common model target changed: " + node.name);
        }
        MethodNode render = ModelStateBatchMethodProof.method(node, RENDER, RENDER_DESCRIPTOR);
        if (render == null) throw new OptimizerAdapterSkippedException("model render unavailable");
        CommonModelStateBatchProof proof = CommonModelStateBatchProof.review(render, node.name, biped);
        if (proof == null) {
            throw new OptimizerAdapterSkippedException("model part sequence has unreviewed callbacks or state changes");
        }

        AbstractInsnNode[] instructions = render.instructions.toArray();
        int scopeLocal = render.maxLocals;
        int failureLocal = scopeLocal + 1;
        LabelNode start = new LabelNode();
        InsnList initialize = new InsnList();
        initialize.add(new InsnNode(Opcodes.ICONST_0));
        initialize.add(new VarInsnNode(Opcodes.ISTORE, scopeLocal));
        render.instructions.insert(initialize);
        render.instructions.insertBefore(proof.first, start);

        int headCalls = 0;
        String headField = biped ? "field_78116_c" : "field_78150_a";
        for (AbstractInsnNode instruction : instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!ModelRendererVboAdapter.TARGET.equals(call.owner)
                || !"func_78785_a".equals(call.name) || !"(F)V".equals(call.desc)) continue;
            AbstractInsnNode loadScale = previousInstruction(call);
            AbstractInsnNode loadPart = previousInstruction(loadScale);
            if (!(loadPart instanceof FieldInsnNode)) continue;
            String field = ((FieldInsnNode) loadPart).name;
            if (!headField.equals(field)) {
                int index = rootIndex(biped, field);
                if (index < 1) throw new IllegalStateException("reviewed common root field changed");
                InsnList advance = new InsnList();
                LabelNode unchanged = new LabelNode();
                advance.add(new VarInsnNode(Opcodes.ILOAD, scopeLocal));
                advance.add(new InsnNode(Opcodes.ICONST_2));
                advance.add(new JumpInsnNode(Opcodes.IF_ICMPNE, unchanged));
                advance.add(new VarInsnNode(Opcodes.ALOAD, 0));
                advance.add(new InsnNode(biped ? Opcodes.ICONST_1 : Opcodes.ICONST_2));
                advance.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, index));
                advance.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "beforeRoot",
                    "(Ljava/lang/Object;II)V", false));
                advance.add(unchanged);
                render.instructions.insertBefore(call, advance);
                continue;
            }
            // Both branches have already run the animation callback. The adult
            // Biped branch has also completed Entity.isSneaking before this point.
            InsnList begin = new InsnList();
            begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
            begin.add(new InsnNode(biped ? Opcodes.ICONST_1 : Opcodes.ICONST_2));
            begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "beginRoots",
                "(Ljava/lang/Object;I)I", false));
            begin.add(new VarInsnNode(Opcodes.ISTORE, scopeLocal));
            render.instructions.insertBefore(call, begin);
            headCalls++;
        }
        if (headCalls != 2) {
            throw new IllegalStateException("reviewed common model head graph changed");
        }
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode normalReturn = new LabelNode();
        InsnList close = new InsnList();
        close.add(end);
        close.add(new VarInsnNode(Opcodes.ILOAD, scopeLocal));
        close.add(new JumpInsnNode(Opcodes.IFEQ, normalReturn));
        close.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "end", "()V", false));
        close.add(normalReturn);
        // TAIL hooks can draw arbitrary models or change client state. Restore
        // before their argument loads, rather than keeping arrays held to RETURN.
        render.instructions.insertBefore(proof.endExclusive, close);
        render.instructions.add(handler);
        render.instructions.add(new VarInsnNode(Opcodes.ASTORE, failureLocal));
        render.instructions.add(new VarInsnNode(Opcodes.ILOAD, scopeLocal));
        LabelNode rethrow = new LabelNode();
        render.instructions.add(new JumpInsnNode(Opcodes.IFEQ, rethrow));
        render.instructions.add(new VarInsnNode(Opcodes.ALOAD, failureLocal));
        render.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "endAfterFailure", "(Ljava/lang/Throwable;)V", false));
        render.instructions.add(rethrow);
        render.instructions.add(new VarInsnNode(Opcodes.ALOAD, failureLocal));
        render.instructions.add(new InsnNode(Opcodes.ATHROW));
        render.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int rootIndex(boolean biped, String field) {
        String[] roots = biped ? new String[]{"field_78116_c", "field_78115_e", "field_178723_h",
            "field_178724_i", "field_178721_j", "field_178722_k", "field_178720_f"}
            : new String[]{"field_78150_a", "field_78148_b", "field_78149_c", "field_78146_d",
                "field_78147_e", "field_78144_f"};
        for (int index = 0; index < roots.length; index++) if (roots[index].equals(field)) return index;
        return -1;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
