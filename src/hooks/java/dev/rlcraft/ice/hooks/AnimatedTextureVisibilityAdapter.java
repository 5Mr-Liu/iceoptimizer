package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Stable final-list and final-dynamic-buffer visibility boundaries. */
final class AnimatedTextureVisibilityAdapter implements OptimizerBytecodeAdapter {
    enum Part { CHUNK_CONTAINER, CHUNK_DRAW, TESSELLATOR }

    private static final String CONTAINER =
        "net/minecraft/client/renderer/ChunkRenderContainer";
    private static final String RENDER_CHUNK =
        "net/minecraft/client/renderer/chunk/RenderChunk";
    private static final String VBO_RENDER_LIST =
        "net/minecraft/client/renderer/VboRenderList";
    private static final String DISPLAY_RENDER_LIST =
        "net/minecraft/client/renderer/RenderList";
    private static final String LAYER = "net/minecraft/util/BlockRenderLayer";
    private static final String TESSELLATOR =
        "net/minecraft/client/renderer/Tessellator";
    private static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/AnimatedTextureVisibilityBootstrap";
    private final Part part;

    AnimatedTextureVisibilityAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (part == Part.CHUNK_CONTAINER) patchContainer(node);
        else if (part == Part.CHUNK_DRAW) patchChunkDraw(node);
        else patchTessellator(node);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void patchChunkDraw(ClassNode node) {
        if (!VBO_RENDER_LIST.equals(node.name)
            && !DISPLAY_RENDER_LIST.equals(node.name)) {
            throw new IllegalStateException(
                "animated visibility draw target changed");
        }
        MethodNode draw = require(node, "func_178001_a", "(L" + LAYER + ";)V");
        if ((draw.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(
                "animated visibility draw boundary is not concrete");
        }
        for (AbstractInsnNode instruction : draw.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (BOOTSTRAP.equals(call.owner) && "terrainDraw".equals(call.name)) {
                throw new IllegalStateException(
                    "animated visibility draw boundary already adapted");
            }
        }
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ALOAD, 0));
        before.add(new VarInsnNode(Opcodes.ALOAD, 1));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "terrainDraw", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        draw.instructions.insert(before);
    }

    private static void patchContainer(ClassNode node) {
        if (!CONTAINER.equals(node.name)) throw new IllegalStateException(
            "animated visibility container target changed");
        MethodNode add = require(node, "func_178002_a", "(L" + RENDER_CHUNK
            + ";L" + LAYER + ";)V");
        int listAdds = 0;
        for (AbstractInsnNode instruction : add.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("java/util/List".equals(call.owner) && "add".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc)) listAdds++;
        }
        if (listAdds != 1 || (add.access & (Opcodes.ACC_STATIC
            | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(
                "animated visibility container graph changed: " + listAdds);
        }
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ALOAD, 1));
        before.add(new VarInsnNode(Opcodes.ALOAD, 2));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "terrainChunk", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        add.instructions.insert(before);
    }

    private static void patchTessellator(ClassNode node) {
        if (!TESSELLATOR.equals(node.name)) throw new IllegalStateException(
            "animated visibility Tessellator target changed");
        MethodNode draw = require(node, "func_78381_a", "()V");
        int uploads = 0;
        for (AbstractInsnNode instruction : draw.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("net/minecraft/client/renderer/WorldVertexBufferUploader".equals(
                call.owner) && "func_181679_a".equals(call.name)) uploads++;
        }
        if (uploads != 1 || (draw.access & (Opcodes.ACC_STATIC
            | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(
                "animated visibility Tessellator graph changed: " + uploads);
        }
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ALOAD, 0));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "bufferDraw", "(Ljava/lang/Object;)V", false));
        draw.instructions.insert(before);
    }

    private static MethodNode require(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate animated visibility method " + name);
            found = method;
        }
        if (found == null) throw new IllegalStateException(
            "missing animated visibility method " + name + desc);
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
