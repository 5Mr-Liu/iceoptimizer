package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keeps the original tick and changes only the room-search frontier data structure. */
final class SoundFiltersSearchAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/tmtravlr/soundfilters/SoundTickHandler";
    static final String POSITION = TARGET + "$ComparablePosition";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/sound/SoundSearchBridge";
    static final String FRONTIER = BRIDGE + "$Frontier";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/sound/SoundPositionAccess";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec spec) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (POSITION.equals(node.name)) {
            node.interfaces.add(ACCESS);
            for (String axis : new String[] { "x", "y", "z" }) {
                MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    "ice$sound" + axis.toUpperCase(java.util.Locale.ROOT), "()F", null, null);
                get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, POSITION, axis, "F"));
                get.instructions.add(new InsnNode(Opcodes.FRETURN));
                node.methods.add(get);
            }
        } else if (TARGET.equals(node.name)) transformTick(node);
        else throw new IllegalStateException("SoundFilters search target changed: " + node.name);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
    private static void transformTick(ClassNode node) {
        MethodNode tick = null;
        String desc = "(Lnet/minecraftforge/fml/common/gameevent/TickEvent$ClientTickEvent;)V";
        for (MethodNode method : node.methods) if ("tick".equals(method.name) && desc.equals(method.desc)) tick = method;
        if (tick == null) throw new IllegalStateException("SoundFilters tick missing");
        MethodNode original = new MethodNode(Opcodes.ASM5, tick.access, tick.name, tick.desc, tick.signature, null);
        tick.accept(original);
        original.name = "ice$originalSoundTick";
        original.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        original.visibleAnnotations = original.invisibleAnnotations = null;
        node.methods.add(original);
        int allocations = 0, contains = 0;
        for (AbstractInsnNode insn : tick.instructions.toArray()) {
            if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW
                && "java/util/LinkedList".equals(((TypeInsnNode) insn).desc)) {
                AbstractInsnNode duplicate = insn.getNext();
                if (duplicate.getOpcode() != Opcodes.DUP) throw new IllegalStateException("Sound frontier allocation changed");
                tick.instructions.remove(duplicate);
                tick.instructions.remove(insn);
                allocations++;
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!"java/util/LinkedList".equals(call.owner)) continue;
                if ("<init>".equals(call.name)) {
                    call.setOpcode(Opcodes.INVOKESTATIC); call.owner = BRIDGE;
                    call.name = "frontier"; call.desc = "()L" + FRONTIER + ";";
                } else { call.owner = FRONTIER; if ("contains".equals(call.name)) contains++; }
            }
        }
        if (allocations != 1 || contains != 6) throw new IllegalStateException("Sound frontier graph changed");
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, tick.access, "tick", desc, tick.signature, null);
        wrapper.visibleAnnotations = tick.visibleAnnotations;
        wrapper.invisibleAnnotations = tick.invisibleAnnotations;
        tick.visibleAnnotations = tick.invisibleAnnotations = null;
        tick.name = "ice$indexedSoundTick";
        tick.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodVisitor v = wrapper;
        v.visitCode();
        Label optimized = new Label(), start = new Label(), end = new Label(), handler = new Label();
        v.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false);
        v.visitJumpInsn(Opcodes.IFNE, optimized);
        v.visitVarInsn(Opcodes.ALOAD, 0); v.visitVarInsn(Opcodes.ALOAD, 1);
        v.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, original.name, desc, false);
        v.visitInsn(Opcodes.RETURN);
        v.visitLabel(optimized);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin", "()I", false);
        v.visitVarInsn(Opcodes.ISTORE, 2);
        v.visitLabel(start);
        v.visitVarInsn(Opcodes.ALOAD, 0); v.visitVarInsn(Opcodes.ALOAD, 1);
        v.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, tick.name, desc, false);
        v.visitLabel(end);
        v.visitVarInsn(Opcodes.ILOAD, 2); v.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "end", "(I)V", false);
        v.visitInsn(Opcodes.RETURN);
        v.visitLabel(handler); v.visitVarInsn(Opcodes.ASTORE, 3);
        v.visitVarInsn(Opcodes.ILOAD, 2); v.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "end", "(I)V", false);
        v.visitVarInsn(Opcodes.ALOAD, 3); v.visitInsn(Opcodes.ATHROW);
        v.visitTryCatchBlock(start, end, handler, null); v.visitMaxs(0, 0); v.visitEnd();
        node.methods.add(wrapper);
    }
}
