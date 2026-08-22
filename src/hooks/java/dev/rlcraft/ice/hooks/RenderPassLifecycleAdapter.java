package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;

/** Exact vanilla EntityRenderer sky/weather/hand call-site observer. */
final class RenderPassLifecycleAdapter implements OptimizerBytecodeAdapter {
    static final String ENTITY_RENDERER =
        "net/minecraft/client/renderer/EntityRenderer";
    static final String RENDER_GLOBAL =
        "net/minecraft/client/renderer/RenderGlobal";
    static final String WORLD_PASS = "func_175068_a";
    static final String WORLD_PASS_DESC = "(IFJ)V";
    static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/RenderPassBootstrap";

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!ENTITY_RENDERER.equals(node.name)) {
            throw new IllegalStateException("EntityRenderer pass target changed");
        }
        MethodNode world = require(node, WORLD_PASS, WORLD_PASS_DESC);
        if ((world.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("EntityRenderer world pass shape changed");
        }
        if (countBootstrapCalls(world) != 0) {
            throw new IllegalStateException("EntityRenderer pass lifecycle already adapted");
        }
        MethodInsnNode sky = uniqueCall(world, RENDER_GLOBAL,
            "func_174976_a", "(FI)V");
        MethodInsnNode weather = uniqueCall(world, ENTITY_RENDERER,
            "func_78474_d", "(F)V");
        MethodInsnNode hand = uniqueCall(world, ENTITY_RENDERER,
            "func_78476_b", "(FI)V");
        wrap(world, sky, "beginSky");
        wrap(world, weather, "beginWeather");
        wrap(world, hand, "beginHand");
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void wrap(MethodNode method, MethodInsnNode call,
                             String beginName) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int[] argumentLocals = new int[arguments.length];
        int next = method.maxLocals;
        for (int index = 0; index < arguments.length; index++) {
            argumentLocals[index] = next;
            next += arguments[index].getSize();
        }
        int receiverLocal = next++;
        int tokenLocal = next;
        next += 2;
        int errorLocal = next++;
        method.maxLocals = next;

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList replacement = new InsnList();
        for (int index = arguments.length - 1; index >= 0; index--) {
            replacement.add(new VarInsnNode(arguments[index].getOpcode(
                Opcodes.ISTORE), argumentLocals[index]));
        }
        replacement.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            beginName, "()J", false));
        replacement.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
        replacement.add(start);
        replacement.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        for (int index = 0; index < arguments.length; index++) {
            replacement.add(new VarInsnNode(arguments[index].getOpcode(
                Opcodes.ILOAD), argumentLocals[index]));
        }
        replacement.add(new MethodInsnNode(call.getOpcode(), call.owner,
            call.name, call.desc, call.itf));
        replacement.add(end);
        replacement.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "end", "(J)V", false));
        replacement.add(new JumpInsnNode(Opcodes.GOTO, done));
        replacement.add(handler);
        replacement.add(new VarInsnNode(Opcodes.ASTORE, errorLocal));
        replacement.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, errorLocal));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "abort", "(JLjava/lang/Throwable;)V", false));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, errorLocal));
        replacement.add(new InsnNode(Opcodes.ATHROW));
        replacement.add(done);
        method.instructions.insertBefore(call, replacement);
        method.instructions.remove(call);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler,
            "java/lang/Throwable"));
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner,
                                             String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) matches.add(call);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("EntityRenderer pass call changed: "
                + owner + '.' + name + descriptor + " count=" + matches.size());
        }
        return matches.get(0);
    }

    private static int countBootstrapCalls(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode
                && BOOTSTRAP.equals(((MethodInsnNode) instruction).owner)) count++;
        }
        return count;
    }

    private static MethodNode require(ClassNode node, String name,
                                      String descriptor) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate " + name + descriptor);
            found = method;
        }
        if (found == null) throw new IllegalStateException(
            "missing " + name + descriptor);
        return found;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
