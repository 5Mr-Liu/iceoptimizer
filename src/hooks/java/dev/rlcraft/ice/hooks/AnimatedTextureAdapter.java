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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact TextureMap animation scope and base-sprite upload adapter. */
final class AnimatedTextureAdapter implements OptimizerBytecodeAdapter {
    enum Part { MAP, SPRITE }

    static final String TEXTURE_MAP =
        "net/minecraft/client/renderer/texture/TextureMap";
    static final String SPRITE =
        "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String TEXTURE_UTIL =
        "net/minecraft/client/renderer/texture/TextureUtil";
    static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/AnimatedTextureBootstrap";
    static final String UPDATE_MAP = "func_94248_c";
    static final String UPDATE_SPRITE = "func_94219_l";
    static final String INTERPOLATE = "func_180599_n";
    static final String VOID_DESC = "()V";
    static final String UPLOAD = "func_147955_a";
    static final String UPLOAD_DESC = "([[IIIIIZZ)V";
    static final String BRIDGE_UPLOAD_DESC = "([[IIIIIZZ)Z";
    static final String ORIGINAL_MAP = "ice$updateAnimationsOriginal";

    private final Part part;

    AnimatedTextureAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass,
                            TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (part == Part.MAP) transformMap(node);
        else transformSprite(node);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformMap(ClassNode node) {
        requireClass(node, TEXTURE_MAP);
        if (find(node, ORIGINAL_MAP, VOID_DESC) != null) {
            throw new IllegalStateException("TextureMap already adapted");
        }
        MethodNode update = require(node, UPDATE_MAP, VOID_DESC);
        if ((update.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("TextureMap update shape changed");
        }
        List<MethodInsnNode> spriteCalls = new ArrayList<MethodInsnNode>(4);
        List<MethodInsnNode> binds = new ArrayList<MethodInsnNode>(5);
        for (AbstractInsnNode instruction : update.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && SPRITE.equals(call.owner) && UPDATE_SPRITE.equals(call.name)
                && VOID_DESC.equals(call.desc)) {
                spriteCalls.add(call);
            } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                && TEXTURE_UTIL.equals(call.owner)
                && "func_94277_a".equals(call.name) && "(I)V".equals(call.desc)) {
                binds.add(call);
            }
        }
        // Vanilla dispatches one atlas sprite.  Reviewed OptiFine G5 dispatches
        // the base plus normal/specular/emissive companions at four distinct
        // sites.  Every site is an observable custom-sprite barrier and must
        // be wrapped independently in its original order.
        boolean vanillaGraph = spriteCalls.size() == 1 && binds.size() == 1;
        boolean optifineG5Graph = spriteCalls.size() == 4
            && binds.size() == 5;
        if (!vanillaGraph && !optifineG5Graph) {
            throw new IllegalStateException("TextureMap animation graph changed: sprite="
                + spriteCalls.size() + ", bind=" + binds.size());
        }
        for (MethodInsnNode bind : binds) {
            update.instructions.insertBefore(bind, new MethodInsnNode(
                Opcodes.INVOKESTATIC, BOOTSTRAP, "textureBarrier", VOID_DESC,
                false));
        }
        for (MethodInsnNode spriteCall : spriteCalls) {
            InsnList before = new InsnList();
            before.add(new InsnNode(Opcodes.DUP));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
                "beforeSprite", "(Ljava/lang/Object;)V", false));
            update.instructions.insertBefore(spriteCall, before);
            update.instructions.insert(spriteCall, new MethodInsnNode(
                Opcodes.INVOKESTATIC, BOOTSTRAP, "afterSprite", VOID_DESC,
                false));
        }

        int access = update.access;
        String signature = update.signature;
        List<String> exceptions = update.exceptions;
        update.name = ORIGINAL_MAP;
        update.access = (update.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(mapWrapper(access, signature, exceptions));
    }

    private static MethodNode mapWrapper(int access, String signature,
                                         List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, UPDATE_MAP,
            VOID_DESC, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "begin",
            "(Ljava/lang/Object;)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 1);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, TEXTURE_MAP, ORIGINAL_MAP,
            VOID_DESC, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "end", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 3);
        code.visitVarInsn(Opcodes.LLOAD, 1);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "abort",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 3);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static void transformSprite(ClassNode node) {
        requireClass(node, SPRITE);
        MethodNode update = require(node, UPDATE_SPRITE, VOID_DESC);
        MethodNode interpolate = require(node, INTERPOLATE, VOID_DESC);
        requireConcreteInstance(update, "update");
        requireConcreteInstance(interpolate, "interpolation");
        replaceUniqueUpload(update);
        replaceUniqueUpload(interpolate);
    }

    private static void requireConcreteInstance(MethodNode method, String role) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("TextureAtlasSprite " + role
                + " method shape changed");
        }
    }

    private static void replaceUniqueUpload(MethodNode method) {
        MethodInsnNode selected = null;
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKESTATIC
                && TEXTURE_UTIL.equals(call.owner) && UPLOAD.equals(call.name)
                && UPLOAD_DESC.equals(call.desc)) {
                selected = call;
                matches++;
            }
        }
        if (matches != 1 || selected == null) {
            throw new IllegalStateException("TextureAtlasSprite upload graph changed in "
                + method.name + ": " + matches);
        }
        int base = method.maxLocals;
        int data = base;
        int width = base + 1;
        int height = base + 2;
        int originX = base + 3;
        int originY = base + 4;
        int blur = base + 5;
        int clamp = base + 6;
        method.maxLocals = base + 7;

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ISTORE, clamp));
        replacement.add(new VarInsnNode(Opcodes.ISTORE, blur));
        replacement.add(new VarInsnNode(Opcodes.ISTORE, originY));
        replacement.add(new VarInsnNode(Opcodes.ISTORE, originX));
        replacement.add(new VarInsnNode(Opcodes.ISTORE, height));
        replacement.add(new VarInsnNode(Opcodes.ISTORE, width));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, data));
        loadUpload(replacement, data, width, height, originX, originY, blur, clamp);
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "tryUpload", BRIDGE_UPLOAD_DESC, false));
        LabelNode accepted = new LabelNode();
        replacement.add(new JumpInsnNode(Opcodes.IFNE, accepted));
        loadUpload(replacement, data, width, height, originX, originY, blur, clamp);
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TEXTURE_UTIL,
            UPLOAD, UPLOAD_DESC, false));
        replacement.add(accepted);
        method.instructions.insertBefore(selected, replacement);
        method.instructions.remove(selected);
    }

    private static void loadUpload(InsnList list, int data, int width, int height,
                                   int originX, int originY, int blur, int clamp) {
        list.add(new VarInsnNode(Opcodes.ALOAD, data));
        list.add(new VarInsnNode(Opcodes.ILOAD, width));
        list.add(new VarInsnNode(Opcodes.ILOAD, height));
        list.add(new VarInsnNode(Opcodes.ILOAD, originX));
        list.add(new VarInsnNode(Opcodes.ILOAD, originY));
        list.add(new VarInsnNode(Opcodes.ILOAD, blur));
        list.add(new VarInsnNode(Opcodes.ILOAD, clamp));
    }

    private static void requireClass(ClassNode node, String expected) {
        if (!expected.equals(node.name)) {
            throw new IllegalStateException("texture animation target changed: " + node.name);
        }
    }

    private static MethodNode require(ClassNode node, String name, String descriptor) {
        MethodNode result = find(node, name, descriptor);
        if (result == null) throw new IllegalStateException("missing " + name + descriptor);
        return result;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        MethodNode result = null;
        int count = 0;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                result = method;
                count++;
            }
        }
        if (count > 1) throw new IllegalStateException("duplicate " + name + descriptor);
        return result;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
