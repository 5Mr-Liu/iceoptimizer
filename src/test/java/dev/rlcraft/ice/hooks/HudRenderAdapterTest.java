package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class HudRenderAdapterTest {
    @Test
    public void wrapsTheCertifiedForgeOverlayAndExactlyFourEvents() {
        byte[] transformed = transform(HudRenderAdapter.Part.OVERLAY,
            HudRenderAdapter.OVERLAY, syntheticOverlay(4));
        assertTrue(hasMethod(transformed, "ice$renderHudOriginal", "(F)V"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP, "begin"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP, "end"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP, "abort"));
        assertEquals(4, calls(transformed, HudRenderAdapter.BOOTSTRAP, "post"));
        assertEquals(2, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "directColor"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAnOverlayWhoseObservableEventGraphChanged() {
        transform(HudRenderAdapter.Part.OVERLAY, HudRenderAdapter.OVERLAY,
            syntheticOverlay(3));
    }

    @Test
    public void wrapsAllFiveVanillaGuiEmittersWithExactFallbacks() {
        byte[] transformed = transform(HudRenderAdapter.Part.GUI,
            HudRenderAdapter.GUI, syntheticGui(true));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "tryTexturedRect"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "tryTexturedRectFloat"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "tryTexturedSprite"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "tryCustomTexture"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "tryScaledTexture"));
        assertEquals(5, calls(transformed, HudRenderAdapter.TESSELLATOR,
            "func_78381_a"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAGuiEmitterWithoutItsSingleLegacyDraw() {
        transform(HudRenderAdapter.Part.GUI, HudRenderAdapter.GUI,
            syntheticGui(false));
    }

    @Test
    public void capturesBothGlyphEmittersWithoutChangingTheirStripTopology() {
        byte[] transformed = transform(HudRenderAdapter.Part.FONT,
            HudRenderAdapter.FONT, syntheticFont(false));
        assertTrue(hasMethod(transformed, "ice$renderStringOriginal",
            "(Ljava/lang/String;FFIZ)I"));
        assertTrue(hasInterface(transformed, HudRenderAdapter.FONT_CACHE_ACCESS));
        assertTrue(hasMethod(transformed, "ice$fontStylesClear", "()Z"));
        assertTrue(hasMethod(transformed, "ice$fontPosX", "()F"));
        assertTrue(hasMethod(transformed, "ice$beginCachedFont", "(FFFFFF)V"));
        assertTrue(hasMethod(transformed, "ice$finishCachedFont", "(F)V"));
        assertEquals(2, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontBegin"));
        assertEquals(8, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontTexCoord"));
        assertEquals(8, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontVertex"));
        assertEquals(2, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontEnd"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontStringBegin"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "tryCachedFontString"));
        assertEquals(2, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontStringEnd"));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP,
            "fontStringAbort"));
    }

    @Test
    public void transformedFontRequiresAndLinksAgainstTheCoreCacheAbi()
        throws Exception {
        byte[] transformed = transform(HudRenderAdapter.Part.FONT,
            HudRenderAdapter.FONT, syntheticFont(false));
        String fontName = HudRenderAdapter.FONT.replace('/', '.');
        String accessName = HudRenderAdapter.FONT_CACHE_ACCESS.replace('/', '.');

        try {
            new IsolatedLoader().define(fontName, transformed);
            fail("FontRenderer unexpectedly linked without its injected Core ABI");
        } catch (NoClassDefFoundError expected) {
            assertTrue(String.valueOf(expected.getMessage())
                .contains("FontRenderCacheAccess"));
        }

        IsolatedLoader coreVisible = new IsolatedLoader();
        Class<?> access = coreVisible.define(accessName,
            readClassBytes(accessName));
        Class<?> font = coreVisible.define(fontName, transformed);
        assertTrue(access.isAssignableFrom(font));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedGlyphEmitterGraph() {
        transform(HudRenderAdapter.Part.FONT, HudRenderAdapter.FONT,
            syntheticFont(true));
    }

    @Test
    public void insertsOneHardBarrierBeforeTheLegacyUploader() {
        byte[] transformed = transform(HudRenderAdapter.Part.TESSELLATOR,
            HudRenderAdapter.TESSELLATOR, syntheticTessellator(1));
        assertEquals(1, calls(transformed, HudRenderAdapter.BOOTSTRAP, "barrier"));
        assertEquals(1, calls(transformed,
            "net/minecraft/client/renderer/WorldVertexBufferUploader",
            "func_181679_a"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAChangedTessellatorUploaderGraph() {
        transform(HudRenderAdapter.Part.TESSELLATOR,
            HudRenderAdapter.TESSELLATOR, syntheticTessellator(2));
    }

    @Test
    public void transformsProductionSrgHudClassesWhenFixtureIsProvided()
        throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge SRG jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            assertReal(jar, HudRenderAdapter.Part.OVERLAY, HudRenderAdapter.OVERLAY);
            assertReal(jar, HudRenderAdapter.Part.GUI, HudRenderAdapter.GUI);
            assertReal(jar, HudRenderAdapter.Part.FONT, HudRenderAdapter.FONT);
            assertReal(jar, HudRenderAdapter.Part.TESSELLATOR,
                HudRenderAdapter.TESSELLATOR);
        } finally {
            jar.close();
        }
    }

    private static void assertReal(JarFile jar, HudRenderAdapter.Part part,
                                   String owner) throws Exception {
        byte[] original = read(jar, owner);
        byte[] transformed = transform(part, owner, original);
        assertFalse(Arrays.equals(original, transformed));
    }

    private static byte[] transform(HudRenderAdapter.Part part, String owner,
                                    byte[] original) {
        byte[] transformed = new HudRenderAdapter(part).transform(
            owner.replace('/', '.'), original,
            new TargetSpec(owner.replace('/', '.'), "modern-hud-stream",
                "test", Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] syntheticOverlay(int posts) {
        ClassWriter writer = writer(HudRenderAdapter.OVERLAY);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_175180_a", "(F)V", null, null);
        method.visitCode();
        for (int index = 0; index < posts; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                HudRenderAdapter.EVENT_BUS, "post",
                "(L" + HudRenderAdapter.EVENT + ";)Z", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < 2; index++) {
            method.visitInsn(Opcodes.FCONST_1);
            method.visitInsn(Opcodes.FCONST_1);
            method.visitInsn(Opcodes.FCONST_1);
            method.visitInsn(Opcodes.FCONST_1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL11", "glColor4f", "(FFFF)V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticGui(boolean complete) {
        ClassWriter writer = writer(HudRenderAdapter.GUI);
        writer.visitField(Opcodes.ACC_PROTECTED, "field_73735_i", "F",
            null, null).visitEnd();
        guiMethod(writer, Opcodes.ACC_PUBLIC, "func_73729_b", "(IIIIII)V", true);
        guiMethod(writer, Opcodes.ACC_PUBLIC, "func_175174_a", "(FFIIII)V",
            complete);
        guiMethod(writer, Opcodes.ACC_PUBLIC, "func_175175_a",
            "(IIL" + HudRenderAdapter.SPRITE + ";II)V", true);
        guiMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "func_146110_a", "(IIFFIIFF)V", true);
        guiMethod(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "func_152125_a", "(IIFFIIIIFF)V", true);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void guiMethod(ClassWriter writer, int access, String name,
                                  String descriptor, boolean draw) {
        MethodVisitor method = writer.visitMethod(access, name, descriptor,
            null, null);
        method.visitCode();
        if (draw) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                HudRenderAdapter.TESSELLATOR, "func_78381_a", "()V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] syntheticFont(boolean changed) {
        ClassWriter writer = writer(HudRenderAdapter.FONT);
        writer.visitField(Opcodes.ACC_PROTECTED, "field_78295_j", "F",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PROTECTED, "field_78296_k", "F",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78291_n", "F",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78292_o", "F",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78306_p", "F",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78305_q", "F",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78303_s", "Z",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78302_t", "Z",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78301_u", "Z",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78300_v", "Z",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_78299_w", "Z",
            null, null).visitEnd();
        glyph(writer, "func_78266_a", "(IZ)F", changed);
        glyph(writer, "func_78277_a", "(CZ)F", false);
        MethodVisitor string = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_180455_b", "(Ljava/lang/String;FFIZ)I", null, null);
        string.visitCode();
        string.visitInsn(Opcodes.ICONST_0);
        string.visitInsn(Opcodes.IRETURN);
        string.visitMaxs(0, 0);
        string.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void glyph(ClassWriter writer, String name,
                              String descriptor, boolean extraVertex) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name,
            descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_5);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            HudRenderAdapter.GL_STATE, "func_187447_r", "(I)V", false);
        for (int vertex = 0; vertex < 4; vertex++) {
            method.visitInsn(Opcodes.FCONST_0);
            method.visitInsn(Opcodes.FCONST_0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                HudRenderAdapter.GL_STATE, "func_187426_b", "(FF)V", false);
            emitVertex(method);
        }
        if (extraVertex) emitVertex(method);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            HudRenderAdapter.GL_STATE, "func_187437_J", "()V", false);
        method.visitInsn(Opcodes.FCONST_0);
        method.visitInsn(Opcodes.FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitVertex(MethodVisitor method) {
        method.visitInsn(Opcodes.FCONST_0);
        method.visitInsn(Opcodes.FCONST_0);
        method.visitInsn(Opcodes.FCONST_0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            HudRenderAdapter.GL_STATE, "func_187435_e", "(FFF)V", false);
    }

    private static byte[] syntheticTessellator(int uploads) {
        ClassWriter writer = writer(HudRenderAdapter.TESSELLATOR);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_78381_a", "()V", null, null);
        method.visitCode();
        for (int index = 0; index < uploads; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/WorldVertexBufferUploader",
                "func_181679_a", "(Ljava/lang/Object;)V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter writer(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null,
            "java/lang/Object", null);
        return writer;
    }

    private static boolean hasMethod(byte[] bytes, final String expected,
                                     final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String desc,
                                                       String signature,
                                                       String[] exceptions) {
                if (expected.equals(name) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name,
                String signature, String superName, String[] interfaces) {
                if (interfaces == null) return;
                for (String value : interfaces) {
                    if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int calls(byte[] bytes, final String owner,
                             final String expected) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode,
                                                          String actualOwner,
                                                          String actualName,
                                                          String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && expected.equals(actualName)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] read(JarFile jar, String owner) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        if (entry == null) throw new IllegalStateException("missing " + owner);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static byte[] readClassBytes(String binaryName) throws Exception {
        String resource = "/" + binaryName.replace('.', '/') + ".class";
        InputStream input = HudRenderAdapterTest.class.getResourceAsStream(resource);
        if (input == null) throw new IllegalStateException("missing " + resource);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static final class IsolatedLoader extends ClassLoader {
        private IsolatedLoader() {
            super(null);
        }

        private Class<?> define(String binaryName, byte[] bytecode) {
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        }
    }
}
