package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact final-emitter adapter for RenderLib 1.4.x's sole traversals. */
final class RenderLibRendererAdapter implements OptimizerBytecodeAdapter {
    enum Part { ENTITY, TESR }

    static final String ENTITY = "meldexun/renderlib/renderer/entity/EntityRenderer";
    static final String ENTITY_LIST =
        "meldexun/renderlib/renderer/entity/EntityRenderList";
    static final String TESR =
        "meldexun/renderlib/renderer/tileentity/TileEntityRenderer";
    static final String TESR_LIST =
        "meldexun/renderlib/renderer/tileentity/TileEntityRenderList";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/renderlib/RenderLibRenderBridge";
    static final String ENTITY_METHOD = "renderEntities";
    static final String TESR_METHOD = "renderTileEntities";
    static final String ORIGINAL = "rlcraftIce$renderTraversalOriginal";
    static final String ENTITY_DESCRIPTOR = "(FL" + ENTITY_LIST + ";)V";
    static final String TESR_DESCRIPTOR = "(FL" + TESR_LIST + ";)V";
    private static final String RENDER_MANAGER =
        "net/minecraft/client/renderer/entity/RenderManager";
    private static final String ENTITY_TYPE = "net/minecraft/entity/Entity";
    private static final String TESR_DISPATCHER =
        "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher";
    private static final String TILE_ENTITY = "net/minecraft/tileentity/TileEntity";

    private final Part part;

    RenderLibRendererAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        String expected = part == Part.ENTITY ? ENTITY : TESR;
        String methodName = part == Part.ENTITY ? ENTITY_METHOD : TESR_METHOD;
        String descriptor = part == Part.ENTITY ? ENTITY_DESCRIPTOR : TESR_DESCRIPTOR;
        if (!expected.equals(node.name)) {
            throw new IllegalStateException("RenderLib renderer target changed: " + node.name);
        }
        MethodNode method = null;
        int matches = 0;
        for (MethodNode candidate : node.methods) {
            if (methodName.equals(candidate.name) && descriptor.equals(candidate.desc)) {
                method = candidate;
                matches++;
            }
            if (ORIGINAL.equals(candidate.name)) {
                throw new IllegalStateException("RenderLib renderer already adapted");
            }
        }
        if (matches != 1 || method == null || (method.access
            & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("RenderLib final traversal changed: " + matches);
        }
        if (part == Part.ENTITY) patchEntityCalls(method);
        else patchTesrCall(method);
        int wrapperAccess = method.access;
        method.name = ORIGINAL;
        method.access |= Opcodes.ACC_SYNTHETIC;
        node.methods.add(wrapper(node.name, wrapperAccess, methodName, descriptor,
            method.signature, method.exceptions, part));
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void patchEntityCalls(MethodNode method) {
        List<MethodInsnNode> normal = new ArrayList<MethodInsnNode>();
        List<MethodInsnNode> multipass = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !RENDER_MANAGER.equals(call.owner)) continue;
            if ("func_188388_a".equals(call.name)
                && ("(L" + ENTITY_TYPE + ";FZ)V").equals(call.desc)) normal.add(call);
            if ("func_188389_a".equals(call.name)
                && ("(L" + ENTITY_TYPE + ";F)V").equals(call.desc)) multipass.add(call);
        }
        if (normal.size() != 2 || multipass.size() != 1) {
            throw new IllegalStateException("RenderLib entity emitter graph changed: normal="
                + normal.size() + ", multipass=" + multipass.size());
        }
        replace(normal.get(0), "renderEntity",
            "(L" + RENDER_MANAGER + ";L" + ENTITY_TYPE + ";FZ)V");
        replace(normal.get(1), "renderOutline",
            "(L" + RENDER_MANAGER + ";L" + ENTITY_TYPE + ";FZ)V");
        replace(multipass.get(0), "renderMultipass",
            "(L" + RENDER_MANAGER + ";L" + ENTITY_TYPE + ";F)V");
    }

    private static void patchTesrCall(MethodNode method) {
        MethodInsnNode selected = null;
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && TESR_DISPATCHER.equals(call.owner)
                && "func_180546_a".equals(call.name)
                && ("(L" + TILE_ENTITY + ";FI)V").equals(call.desc)) {
                selected = call;
                matches++;
            }
        }
        if (matches != 1 || selected == null) {
            throw new IllegalStateException("RenderLib TESR emitter graph changed: " + matches);
        }
        replace(selected, "renderTileEntity", "(L" + TESR_DISPATCHER
            + ";L" + TILE_ENTITY + ";FI)V");
    }

    private static void replace(MethodInsnNode call, String name, String descriptor) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = BRIDGE;
        call.name = name;
        call.desc = descriptor;
        call.itf = false;
    }

    private static MethodNode wrapper(String owner, int access, String publicName,
                                      String descriptor, String signature,
                                      List<String> exceptions, Part part) {
        MethodNode wrapper = new MethodNode(Opcodes.ASM5, access, publicName,
            descriptor, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = wrapper;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE,
            part == Part.ENTITY ? "beginEntityTraversal" : "beginTesrTraversal",
            "(Ljava/lang/Object;)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 3);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, ORIGINAL, descriptor, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "endTraversal", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 5);
        code.visitVarInsn(Opcodes.LLOAD, 3);
        code.visitVarInsn(Opcodes.ALOAD, 5);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "abortTraversal",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 5);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return wrapper;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
