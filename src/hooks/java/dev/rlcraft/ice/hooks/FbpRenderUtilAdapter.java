package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reviewed FBP 2.4.1 cube helper; leaves Block and Flame emitters untouched. */
final class FbpRenderUtilAdapter implements OptimizerBytecodeAdapter {
    static final String OWNER = "com/TominoCZ/FBP/util/FBPRenderUtil";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/particle/FbpCubeRenderBridge";
    static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder";
    static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    static final String FORMAT = "net/minecraft/client/renderer/vertex/VertexFormat";
    static final String PREFIX = "(L" + BUFFER + ";[Lnet/minecraft/util/math/Vec2f;FFF";
    static final String SUFFIX = "Lcom/TominoCZ/FBP/vector/FBPVector3d;IIFFFFZ)V";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!OWNER.equals(node.name)) throw new IllegalStateException("unknown FBP util");
        adapt(node, "renderCubeShaded_S", PREFIX + "D" + SUFFIX);
        adapt(node, "renderCubeShaded_WH", PREFIX + "DD" + SUFFIX);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void adapt(ClassNode node, String name, String descriptor) {
        MethodNode original = null;
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("ice$" + name)) {
                throw new IllegalStateException("FBP cube helper already adapted");
            }
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (original != null) throw new IllegalStateException("duplicate FBP helper");
                original = method;
            }
        }
        if (original == null || (original.access & Opcodes.ACC_STATIC) == 0
            || !original.tryCatchBlocks.isEmpty()) {
            throw new IllegalStateException("FBP cube helper changed: " + name);
        }
        MethodNode optimized = new MethodNode(Opcodes.ASM5, original.access,
            "ice$" + name + "Optimized", descriptor, original.signature,
            original.exceptions.toArray(new String[original.exceptions.size()]));
        original.accept(optimized);
        optimized.name = "ice$" + name + "Optimized";
        optimized.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        List<AbstractInsnNode> code = executable(optimized);
        // The first two sequences are byte-for-byte the same flush/bind/begin.
        // No vertex or state write occurs between them, so the second draw is empty.
        int firstEnd = firstBegin(code);
        if (firstEnd < 0 || firstEnd * 2 + 2 > code.size()) {
            throw new IllegalStateException("FBP cube prefix changed");
        }
        int length = firstEnd + 1;
        requirePrefix(code, length);
        for (int index = 0; index < length; index++) {
            if (!sameInstruction(code.get(index), code.get(index + length))) {
                throw new IllegalStateException("FBP repeated cube prefix changed");
            }
        }
        int draws = 0;
        int begins = 0;
        int cubes = 0;
        for (AbstractInsnNode instruction : code) {
            if (instruction instanceof JumpInsnNode) {
                throw new IllegalStateException("FBP cube helper control flow changed");
            }
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (isDraw(call)) draws++;
            if (isBegin(call)) begins++;
            if (OWNER.equals(call.owner) && call.name.equals(
                name.equals("renderCubeShaded_S") ? "putCube_S" : "putCube_WH")) cubes++;
        }
        if (draws != 3 || begins != 3 || cubes != 1 || length != 11) {
            throw new IllegalStateException("FBP cube draw/begin graph changed");
        }
        for (int index = length; index < length * 2; index++) {
            optimized.instructions.remove(code.get(index));
        }
        int drawIndex = 0;
        for (AbstractInsnNode instruction : optimized.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (isDraw(call)) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = drawIndex++ == 0 ? "drawBoundary" : "drawCube";
                call.desc = "(L" + TESSELLATOR + ";)V";
                call.itf = false;
            }
        }
        String legacyName = "ice$" + name + "Legacy";
        original.name = legacyName;
        original.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        node.methods.add(optimized);
        node.methods.add(wrapper(name, descriptor, legacyName, optimized.name));
    }

    private static MethodNode wrapper(String name, String descriptor,
                                      String legacy, String optimized) {
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        LabelNode fallback = new LabelNode();
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
            "isEnabled", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        invoke(method, descriptor, optimized);
        method.visitInsn(Opcodes.RETURN);
        method.instructions.add(fallback);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        invoke(method, descriptor, legacy);
        method.visitInsn(Opcodes.RETURN);
        return method;
    }

    private static void invoke(MethodNode method, String descriptor, String target) {
        int local = 0;
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, target, descriptor, false);
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) result.add(instruction);
        }
        return result;
    }

    private static int firstBegin(List<AbstractInsnNode> code) {
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode
                && isBegin((MethodInsnNode) code.get(i))) return i;
        }
        return -1;
    }

    private static void requirePrefix(List<AbstractInsnNode> code, int length) {
        AbstractInsnNode[] expected = {
            new MethodInsnNode(Opcodes.INVOKESTATIC, TESSELLATOR, "func_178181_a",
                "()L" + TESSELLATOR + ";", false),
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TESSELLATOR, "func_78381_a", "()V", false),
            new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft",
                "func_71410_x", "()Lnet/minecraft/client/Minecraft;", false),
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Minecraft",
                "func_175598_ae", "()Lnet/minecraft/client/renderer/entity/RenderManager;", false),
            new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/renderer/entity/RenderManager",
                "field_78724_e", "Lnet/minecraft/client/renderer/texture/TextureManager;"),
            new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/client/renderer/texture/TextureMap",
                "field_110575_b", "Lnet/minecraft/util/ResourceLocation;"),
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/renderer/texture/TextureManager",
                "func_110577_a", "(Lnet/minecraft/util/ResourceLocation;)V", false),
            new VarInsnNode(Opcodes.ALOAD, 0),
            new IntInsnNode(Opcodes.BIPUSH, 7),
            new FieldInsnNode(Opcodes.GETSTATIC, "com/TominoCZ/FBP/FBP",
                "POSITION_TEX_COLOR_LMAP_NORMAL", "L" + FORMAT + ";"),
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BUFFER, "func_181668_a",
                "(IL" + FORMAT + ";)V", false)
        };
        if (length != expected.length) throw new IllegalStateException("FBP cube prefix length changed");
        for (int i = 0; i < expected.length; i++) {
            if (!sameInstruction(code.get(i), expected[i])) {
                throw new IllegalStateException("FBP cube flush/atlas/format prefix changed");
            }
        }
    }

    private static boolean isBegin(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && BUFFER.equals(call.owner)
            && "func_181668_a".equals(call.name)
            && ("(IL" + FORMAT + ";)V").equals(call.desc);
    }

    private static boolean isDraw(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && TESSELLATOR.equals(call.owner)
            && "func_78381_a".equals(call.name) && "()V".equals(call.desc);
    }

    private static boolean sameInstruction(AbstractInsnNode a, AbstractInsnNode b) {
        if (a.getOpcode() != b.getOpcode() || a.getType() != b.getType()) return false;
        if (a instanceof MethodInsnNode) {
            MethodInsnNode x = (MethodInsnNode) a, y = (MethodInsnNode) b;
            return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc)
                && x.itf == y.itf;
        }
        if (a instanceof FieldInsnNode) {
            FieldInsnNode x = (FieldInsnNode) a, y = (FieldInsnNode) b;
            return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc);
        }
        if (a instanceof VarInsnNode) return ((VarInsnNode) a).var == ((VarInsnNode) b).var;
        if (a instanceof IntInsnNode) return ((IntInsnNode) a).operand == ((IntInsnNode) b).operand;
        return false;
    }
}
