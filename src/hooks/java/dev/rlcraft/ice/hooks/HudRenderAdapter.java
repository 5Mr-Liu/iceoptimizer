package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact Forge HUD, vanilla GUI quad, font glyph and legacy draw barriers. */
final class HudRenderAdapter implements OptimizerBytecodeAdapter {
    enum Part { OVERLAY, GUI, FONT, TESSELLATOR }

    static final String OVERLAY = "net/minecraftforge/client/GuiIngameForge";
    static final String GUI = "net/minecraft/client/gui/Gui";
    static final String FONT = "net/minecraft/client/gui/FontRenderer";
    static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    static final String SPRITE =
        "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";
    static final String EVENT_BUS =
        "net/minecraftforge/fml/common/eventhandler/EventBus";
    static final String EVENT = "net/minecraftforge/fml/common/eventhandler/Event";
    static final String BOOTSTRAP = "dev/rlcraft/ice/hooks/HudRenderBootstrap";
    static final String FONT_CACHE_ACCESS =
        "dev/rlcraft/ice/optimizer/compat/hud/FontRenderCacheAccess";
    private static final String OVERLAY_RENDER = "func_175180_a";
    private static final String OVERLAY_DESC = "(F)V";
    private static final String ORIGINAL_OVERLAY = "ice$renderHudOriginal";
    private static final String FONT_STRING = "func_180455_b";
    private static final String FONT_STRING_DESC = "(Ljava/lang/String;FFIZ)I";
    private static final String ORIGINAL_FONT_STRING = "ice$renderStringOriginal";
    private static final String FONT_POS_X = "field_78295_j";
    private static final String FONT_POS_Y = "field_78296_k";
    private static final String FONT_RED = "field_78291_n";
    private static final String FONT_GREEN = "field_78292_o";
    private static final String FONT_BLUE = "field_78306_p";
    private static final String FONT_ALPHA = "field_78305_q";
    private static final String FONT_RANDOM = "field_78303_s";
    private static final String FONT_BOLD = "field_78302_t";
    private static final String FONT_ITALIC = "field_78301_u";
    private static final String FONT_UNDERLINE = "field_78300_v";
    private static final String FONT_STRIKETHROUGH = "field_78299_w";
    private final Part part;

    HudRenderAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass,
                            TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        switch (part) {
            case OVERLAY: transformOverlay(node); break;
            case GUI: transformGui(node); break;
            case FONT: transformFont(node); break;
            case TESSELLATOR: transformTessellator(node); break;
            default: throw new IllegalStateException("unknown HUD target");
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformOverlay(ClassNode node) {
        requireClass(node, OVERLAY);
        if (find(node, ORIGINAL_OVERLAY, OVERLAY_DESC) != null) {
            throw new IllegalStateException("Forge HUD already adapted");
        }
        MethodNode render = require(node, OVERLAY_RENDER, OVERLAY_DESC);
        requireConcreteInstance(render, "Forge HUD render");
        int posts = 0;
        int directColor = 0;
        int unexpectedDirectGl = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && EVENT_BUS.equals(call.owner) && "post".equals(call.name)
                    && ("(L" + EVENT + ";)Z").equals(call.desc)) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = BOOTSTRAP;
                    call.name = "post";
                    call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
                    call.itf = false;
                    posts++;
                } else if ("org/lwjgl/opengl/GL11".equals(call.owner)) {
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && "glColor4f".equals(call.name)
                        && "(FFFF)V".equals(call.desc)) {
                        call.owner = BOOTSTRAP;
                        call.name = "directColor";
                        directColor++;
                    } else {
                        unexpectedDirectGl++;
                    }
                }
            }
        }
        if (posts != 4 || directColor != 2 || unexpectedDirectGl != 0) {
            throw new IllegalStateException("Forge HUD graph changed: events="
                + posts + ", directColor=" + directColor + ", rawGl="
                + unexpectedDirectGl);
        }
        int access = render.access;
        String signature = render.signature;
        List<String> exceptions = render.exceptions;
        render.name = ORIGINAL_OVERLAY;
        render.access = privateSynthetic(render.access);
        node.methods.add(overlayWrapper(access, signature, exceptions));
    }

    private static MethodNode overlayWrapper(int access, String signature,
                                             List<String> exceptions) {
        MethodNode method = method(access, OVERLAY_RENDER, OVERLAY_DESC,
            signature, exceptions);
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "begin",
            "(Ljava/lang/Object;F)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 2);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.FLOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, OVERLAY, ORIGINAL_OVERLAY,
            OVERLAY_DESC, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "end", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitVarInsn(Opcodes.LLOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "abort",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static void transformGui(ClassNode node) {
        requireClass(node, GUI);
        wrapGui(node, "func_73729_b", "(IIIIII)V", "ice$hudRectOriginal",
            "tryTexturedRect", "(Ljava/lang/Object;IIIIIIF)Z", false, true);
        wrapGui(node, "func_175174_a", "(FFIIII)V", "ice$hudRectFloatOriginal",
            "tryTexturedRectFloat", "(Ljava/lang/Object;FFIIIIF)Z", false, true);
        wrapGui(node, "func_175175_a", "(IIL" + SPRITE + ";II)V",
            "ice$hudSpriteOriginal", "tryTexturedSprite",
            "(Ljava/lang/Object;IILjava/lang/Object;IIF)Z", false, true);
        wrapGui(node, "func_146110_a", "(IIFFIIFF)V",
            "ice$hudCustomOriginal", "tryCustomTexture", "(IIFFIIFF)Z",
            true, false);
        wrapGui(node, "func_152125_a", "(IIFFIIIIFF)V",
            "ice$hudScaledOriginal", "tryScaledTexture", "(IIFFIIIIFF)Z",
            true, false);
    }

    private static void wrapGui(ClassNode node, String name, String descriptor,
                                String originalName, String bridgeName,
                                String bridgeDescriptor, boolean expectedStatic,
                                boolean loadZ) {
        if (find(node, originalName, descriptor) != null) {
            throw new IllegalStateException("GUI method already adapted " + name);
        }
        MethodNode original = require(node, name, descriptor);
        boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
        if (isStatic != expectedStatic
            || (original.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("GUI method shape changed " + name);
        }
        int draws = countCalls(original, TESSELLATOR, "func_78381_a", "()V");
        if (draws != 1) {
            throw new IllegalStateException("GUI emitter graph changed " + name
                + ": draw=" + draws);
        }
        int access = original.access;
        String signature = original.signature;
        List<String> exceptions = original.exceptions;
        original.name = originalName;
        original.access = privateSynthetic(original.access);
        node.methods.add(guiWrapper(access, name, descriptor, originalName,
            bridgeName, bridgeDescriptor, signature, exceptions, isStatic, loadZ));
    }

    private static MethodNode guiWrapper(int access, String name, String descriptor,
                                         String originalName, String bridgeName,
                                         String bridgeDescriptor, String signature,
                                         List<String> exceptions, boolean isStatic,
                                         boolean loadZ) {
        MethodNode method = method(access, name, descriptor, signature, exceptions);
        MethodVisitor code = method;
        code.visitCode();
        int local = 0;
        if (!isStatic) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            local = 1;
        }
        org.objectweb.asm.Type[] arguments =
            org.objectweb.asm.Type.getArgumentTypes(descriptor);
        for (org.objectweb.asm.Type argument : arguments) {
            code.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        if (loadZ) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitFieldInsn(Opcodes.GETFIELD, GUI, "field_73735_i", "F");
        }
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, bridgeName,
            bridgeDescriptor, false);
        Label fallback = new Label();
        code.visitJumpInsn(Opcodes.IFEQ, fallback);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(fallback);
        local = 0;
        if (!isStatic) {
            code.visitVarInsn(Opcodes.ALOAD, 0);
            local = 1;
        }
        for (org.objectweb.asm.Type argument : arguments) {
            code.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        code.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
            GUI, originalName, descriptor, false);
        code.visitInsn(Opcodes.RETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static void transformFont(ClassNode node) {
        requireClass(node, FONT);
        addFontCacheAccess(node);
        replaceGlyphEmitter(require(node, "func_78266_a", "(IZ)F"));
        replaceGlyphEmitter(require(node, "func_78277_a", "(CZ)F"));
        MethodNode string = require(node, FONT_STRING, FONT_STRING_DESC);
        requireConcreteInstance(string, "FontRenderer string");
        if (find(node, ORIGINAL_FONT_STRING, FONT_STRING_DESC) != null) {
            throw new IllegalStateException("FontRenderer already adapted");
        }
        int access = string.access;
        String signature = string.signature;
        List<String> exceptions = string.exceptions;
        string.name = ORIGINAL_FONT_STRING;
        string.access = privateSynthetic(string.access);
        node.methods.add(fontStringWrapper(access, signature, exceptions));
    }

    private static void replaceGlyphEmitter(MethodNode method) {
        int begin = 0;
        int texCoord = 0;
        int vertex = 0;
        int end = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKESTATIC
                || !GL_STATE.equals(call.owner)) continue;
            if ("func_187447_r".equals(call.name) && "(I)V".equals(call.desc)) {
                call.owner = BOOTSTRAP;
                call.name = "fontBegin";
                begin++;
            } else if ("func_187426_b".equals(call.name)
                && "(FF)V".equals(call.desc)) {
                call.owner = BOOTSTRAP;
                call.name = "fontTexCoord";
                texCoord++;
            } else if ("func_187435_e".equals(call.name)
                && "(FFF)V".equals(call.desc)) {
                call.owner = BOOTSTRAP;
                call.name = "fontVertex";
                vertex++;
            } else if ("func_187437_J".equals(call.name)
                && "()V".equals(call.desc)) {
                call.owner = BOOTSTRAP;
                call.name = "fontEnd";
                end++;
            }
        }
        if (begin != 1 || texCoord != 4 || vertex != 4 || end != 1) {
            throw new IllegalStateException("Font glyph graph changed in "
                + method.name + ": " + begin + '/' + texCoord + '/'
                + vertex + '/' + end);
        }
    }

    private static MethodNode fontStringWrapper(int access, String signature,
                                                List<String> exceptions) {
        MethodNode method = method(access, FONT_STRING, FONT_STRING_DESC,
            signature, exceptions);
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.FLOAD, 2);
        code.visitVarInsn(Opcodes.FLOAD, 3);
        code.visitVarInsn(Opcodes.ILOAD, 4);
        code.visitVarInsn(Opcodes.ILOAD, 5);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "fontStringBegin",
            "(Ljava/lang/Object;Ljava/lang/String;FFIZ)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 6);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.LLOAD, 6);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.FLOAD, 2);
        code.visitVarInsn(Opcodes.FLOAD, 3);
        code.visitVarInsn(Opcodes.ILOAD, 4);
        code.visitVarInsn(Opcodes.ILOAD, 5);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "tryCachedFontString",
            "(JLjava/lang/Object;Ljava/lang/String;FFIZ)I", false);
        code.visitVarInsn(Opcodes.ISTORE, 8);
        Label cacheMiss = new Label();
        code.visitVarInsn(Opcodes.ILOAD, 8);
        code.visitLdcInsn(Integer.MIN_VALUE);
        code.visitJumpInsn(Opcodes.IF_ICMPEQ, cacheMiss);
        code.visitVarInsn(Opcodes.LLOAD, 6);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "fontStringEnd",
            "(J)V", false);
        code.visitVarInsn(Opcodes.ILOAD, 8);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(cacheMiss);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.FLOAD, 2);
        code.visitVarInsn(Opcodes.FLOAD, 3);
        code.visitVarInsn(Opcodes.ILOAD, 4);
        code.visitVarInsn(Opcodes.ILOAD, 5);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, FONT, ORIGINAL_FONT_STRING,
            FONT_STRING_DESC, false);
        code.visitVarInsn(Opcodes.ISTORE, 8);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 6);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "fontStringEnd",
            "(J)V", false);
        code.visitVarInsn(Opcodes.ILOAD, 8);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 9);
        code.visitVarInsn(Opcodes.LLOAD, 6);
        code.visitVarInsn(Opcodes.ALOAD, 9);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "fontStringAbort",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 9);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static void addFontCacheAccess(ClassNode node) {
        if (node.interfaces.contains(FONT_CACHE_ACCESS)) {
            throw new IllegalStateException("FontRenderer cache ABI duplicate");
        }
        requireField(node, FONT_POS_X, "F");
        requireField(node, FONT_POS_Y, "F");
        requireField(node, FONT_RED, "F");
        requireField(node, FONT_GREEN, "F");
        requireField(node, FONT_BLUE, "F");
        requireField(node, FONT_ALPHA, "F");
        requireField(node, FONT_RANDOM, "Z");
        requireField(node, FONT_BOLD, "Z");
        requireField(node, FONT_ITALIC, "Z");
        requireField(node, FONT_UNDERLINE, "Z");
        requireField(node, FONT_STRIKETHROUGH, "Z");
        node.interfaces.add(FONT_CACHE_ACCESS);

        MethodVisitor styles = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$fontStylesClear", "()Z", null, null);
        styles.visitCode();
        Label dirty = new Label();
        String[] styleFields = {FONT_RANDOM, FONT_BOLD, FONT_ITALIC,
            FONT_UNDERLINE, FONT_STRIKETHROUGH};
        for (String field : styleFields) {
            styles.visitVarInsn(Opcodes.ALOAD, 0);
            styles.visitFieldInsn(Opcodes.GETFIELD, FONT, field, "Z");
            styles.visitJumpInsn(Opcodes.IFNE, dirty);
        }
        styles.visitInsn(Opcodes.ICONST_1);
        styles.visitInsn(Opcodes.IRETURN);
        styles.visitLabel(dirty);
        styles.visitInsn(Opcodes.ICONST_0);
        styles.visitInsn(Opcodes.IRETURN);
        styles.visitMaxs(0, 0);
        styles.visitEnd();
        node.methods.add((MethodNode) styles);

        MethodVisitor pos = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$fontPosX", "()F", null, null);
        pos.visitCode();
        pos.visitVarInsn(Opcodes.ALOAD, 0);
        pos.visitFieldInsn(Opcodes.GETFIELD, FONT, FONT_POS_X, "F");
        pos.visitInsn(Opcodes.FRETURN);
        pos.visitMaxs(0, 0);
        pos.visitEnd();
        node.methods.add((MethodNode) pos);

        MethodVisitor begin = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$beginCachedFont", "(FFFFFF)V", null, null);
        begin.visitCode();
        putFloatField(begin, FONT_POS_X, 1);
        putFloatField(begin, FONT_POS_Y, 2);
        putFloatField(begin, FONT_RED, 3);
        putFloatField(begin, FONT_GREEN, 4);
        putFloatField(begin, FONT_BLUE, 5);
        putFloatField(begin, FONT_ALPHA, 6);
        begin.visitInsn(Opcodes.RETURN);
        begin.visitMaxs(0, 0);
        begin.visitEnd();
        node.methods.add((MethodNode) begin);

        MethodVisitor finish = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            "ice$finishCachedFont", "(F)V", null, null);
        finish.visitCode();
        putFloatField(finish, FONT_POS_X, 1);
        finish.visitInsn(Opcodes.RETURN);
        finish.visitMaxs(0, 0);
        finish.visitEnd();
        node.methods.add((MethodNode) finish);
    }

    private static void putFloatField(MethodVisitor method, String field,
                                      int local) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.FLOAD, local);
        method.visitFieldInsn(Opcodes.PUTFIELD, FONT, field, "F");
    }

    private static void transformTessellator(ClassNode node) {
        requireClass(node, TESSELLATOR);
        MethodNode draw = require(node, "func_78381_a", "()V");
        requireConcreteInstance(draw, "Tessellator draw");
        int uploadCalls = 0;
        for (AbstractInsnNode instruction : draw.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("net/minecraft/client/renderer/WorldVertexBufferUploader".equals(call.owner)
                && "func_181679_a".equals(call.name)) uploadCalls++;
        }
        if (uploadCalls != 1) {
            throw new IllegalStateException("Tessellator draw graph changed: "
                + uploadCalls);
        }
        InsnList barrier = new InsnList();
        barrier.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP,
            "barrier", "()V", false));
        draw.instructions.insert(barrier);
    }

    private static int countCalls(MethodNode method, String owner, String name,
                                  String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)
                && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static void requireClass(ClassNode node, String expected) {
        if (!expected.equals(node.name)) {
            throw new IllegalStateException("HUD target changed: " + node.name);
        }
    }

    private static void requireConcreteInstance(MethodNode method, String detail) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(detail + " is not a concrete instance method");
        }
    }

    private static MethodNode require(ClassNode node, String name, String descriptor) {
        MethodNode method = find(node, name, descriptor);
        if (method == null) throw new IllegalStateException("missing " + name + descriptor);
        return method;
    }

    private static void requireField(ClassNode node, String name,
                                     String descriptor) {
        for (org.objectweb.asm.tree.FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) return;
        }
        throw new IllegalStateException("missing " + name + descriptor);
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException("duplicate " + name + descriptor);
            found = method;
        }
        return found;
    }

    private static int privateSynthetic(int access) {
        return (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    }

    private static MethodNode method(int access, String name, String descriptor,
                                     String signature, List<String> exceptions) {
        return new MethodNode(Opcodes.ASM5, access, name, descriptor, signature,
            exceptions == null ? null : exceptions.toArray(new String[exceptions.size()]));
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
