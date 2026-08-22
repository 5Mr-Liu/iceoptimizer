package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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

public final class AnimatedTextureAdapterTest {
    @Test
    public void wrapsMapAndPlacesBarriersAroundTheOnlySpriteDispatch() {
        byte[] result = transform(AnimatedTextureAdapter.Part.MAP,
            AnimatedTextureAdapter.TEXTURE_MAP, syntheticMap());
        assertTrue(hasMethod(result, AnimatedTextureAdapter.ORIGINAL_MAP, "()V"));
        assertEquals(1, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "begin"));
        assertEquals(1, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "end"));
        assertEquals(1, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "abort"));
        assertEquals(1, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "beforeSprite"));
        assertEquals(1, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "afterSprite"));
        assertEquals(1, calls(result, AnimatedTextureAdapter.BOOTSTRAP,
            "textureBarrier"));
    }

    @Test
    public void guardsBothBaseSpriteUploadsWithExactLegacyFallbacks() {
        byte[] result = transform(AnimatedTextureAdapter.Part.SPRITE,
            AnimatedTextureAdapter.SPRITE, syntheticSprite());
        assertEquals(2, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "tryUpload"));
        assertEquals(2, calls(result, AnimatedTextureAdapter.TEXTURE_UTIL,
            AnimatedTextureAdapter.UPLOAD));
    }

    @Test
    public void acceptsReviewedTransformerVisibilityChangesWithTheExactTwoUploadGraph() {
        byte[] result = transform(AnimatedTextureAdapter.Part.SPRITE,
            AnimatedTextureAdapter.SPRITE, syntheticSprite(false, Opcodes.ACC_PUBLIC));
        assertEquals(2, calls(result, AnimatedTextureAdapter.BOOTSTRAP, "tryUpload"));
        assertEquals(2, calls(result, AnimatedTextureAdapter.TEXTURE_UTIL,
            AnimatedTextureAdapter.UPLOAD));
    }

    @Test
    public void wrapsAllFourReviewedOptifineCompanionSpriteDispatches() {
        byte[] result = transform(AnimatedTextureAdapter.Part.MAP,
            AnimatedTextureAdapter.TEXTURE_MAP, syntheticMap(4, 5));
        assertEquals(4, calls(result, AnimatedTextureAdapter.BOOTSTRAP,
            "beforeSprite"));
        assertEquals(4, calls(result, AnimatedTextureAdapter.BOOTSTRAP,
            "afterSprite"));
        assertEquals(5, calls(result, AnimatedTextureAdapter.BOOTSTRAP,
            "textureBarrier"));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesAChangedSpriteUploadGraph() {
        transform(AnimatedTextureAdapter.Part.SPRITE,
            AnimatedTextureAdapter.SPRITE, syntheticSpriteWithExtraUpload());
    }

    @Test
    public void transformsProductionSrgTextureClassesWhenProvided() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar");
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>", configured != null);
        JarFile jar = new JarFile(new File(configured));
        try {
            byte[] map = read(jar, AnimatedTextureAdapter.TEXTURE_MAP);
            byte[] mapResult = transform(AnimatedTextureAdapter.Part.MAP,
                AnimatedTextureAdapter.TEXTURE_MAP, map);
            assertFalse(Arrays.equals(map, mapResult));
            assertEquals(1, calls(mapResult, AnimatedTextureAdapter.BOOTSTRAP,
                "beforeSprite"));

            byte[] sprite = read(jar, AnimatedTextureAdapter.SPRITE);
            byte[] spriteResult = transform(AnimatedTextureAdapter.Part.SPRITE,
                AnimatedTextureAdapter.SPRITE, sprite);
            assertFalse(Arrays.equals(sprite, spriteResult));
            assertEquals(2, calls(spriteResult, AnimatedTextureAdapter.BOOTSTRAP,
                "tryUpload"));
        } finally {
            jar.close();
        }
    }

    @Test
    public void reviewedOptifinePatchKeepsTheCertifiedAnimationCallGraph()
        throws Exception {
        String optifine = System.getProperty("ice.optifine.jar", "").trim();
        String client = System.getProperty("ice.minecraft.client.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar and -PminecraftClientJar",
            !optifine.isEmpty() && !client.isEmpty());
        URLClassLoader loader = new URLClassLoader(
            new URL[] {new File(optifine).toURI().toURL(),
                new File(client).toURI().toURL()},
            AnimatedTextureAdapterTest.class.getClassLoader());
        JarFile vanilla = new JarFile(new File(client));
        try {
            Class<?> transformerType = Class.forName(
                "optifine.OptiFineClassTransformer", true, loader);
            Object transformer = transformerType.newInstance();
            Method transform = transformerType.getMethod("transform",
                String.class, String.class, byte[].class);
            byte[] map = patched(transform, transformer, "cdp",
                "net.minecraft.client.renderer.texture.TextureMap",
                read(vanilla, "cdp"));
            assertEquals(4, callsInMethod(map, "d", "()V", "cdq", "j", "()V"));
            assertEquals(5, callsInMethod(map, "d", "()V", "cdt", "b", "(I)V"));

            byte[] sprite = patched(transform, transformer, "cdq",
                "net.minecraft.client.renderer.texture.TextureAtlasSprite",
                read(vanilla, "cdq"));
            assertEquals(1, callsInMethod(sprite, "j", "()V", "cdt", "a",
                AnimatedTextureAdapter.UPLOAD_DESC));
            assertEquals(1, callsInMethod(sprite, "n", "()V", "cdt", "a",
                AnimatedTextureAdapter.UPLOAD_DESC));
        } finally {
            vanilla.close();
            loader.close();
        }
    }

    @Test
    public void reviewedOptifinePostPatchSpriteAcceptsTheProductionAdapter()
        throws Exception {
        OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip();
        try {
            byte[] remapped = support.patchAndRemap("cdq",
                "net.minecraft.client.renderer.texture.TextureAtlasSprite");
            byte[] transformed = transform(AnimatedTextureAdapter.Part.SPRITE,
                AnimatedTextureAdapter.SPRITE, remapped);
            assertEquals(2, calls(transformed, AnimatedTextureAdapter.BOOTSTRAP,
                "tryUpload"));
            assertEquals(2, calls(transformed, AnimatedTextureAdapter.TEXTURE_UTIL,
                AnimatedTextureAdapter.UPLOAD));
        } finally {
            support.close();
        }
    }

    private static byte[] transform(AnimatedTextureAdapter.Part part,
                                    String className, byte[] original) {
        byte[] result = new AnimatedTextureAdapter(part).transform(
            className.replace('/', '.'), original,
            new TargetSpec(className.replace('/', '.'), "modern-texture-stream",
                "test", Collections.<String>emptySet()));
        new ClassReader(result);
        return result;
    }

    private static byte[] syntheticMap() {
        return syntheticMap(1, 1);
    }

    private static byte[] syntheticMap(int spriteCalls, int textureBinds) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            AnimatedTextureAdapter.TEXTURE_MAP, null, "java/lang/Object", null);
        MethodVisitor update = writer.visitMethod(Opcodes.ACC_PUBLIC,
            AnimatedTextureAdapter.UPDATE_MAP, "()V", null, null);
        update.visitCode();
        for (int bind = 0; bind < textureBinds; bind++) {
            update.visitInsn(Opcodes.ICONST_1);
            update.visitMethodInsn(Opcodes.INVOKESTATIC,
                AnimatedTextureAdapter.TEXTURE_UTIL, "func_94277_a", "(I)V",
                false);
        }
        for (int call = 0; call < spriteCalls; call++) {
            update.visitInsn(Opcodes.ACONST_NULL);
            update.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                AnimatedTextureAdapter.SPRITE,
                AnimatedTextureAdapter.UPDATE_SPRITE, "()V", false);
        }
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticSprite() {
        return syntheticSprite(false);
    }

    private static byte[] syntheticSpriteWithExtraUpload() {
        return syntheticSprite(true);
    }

    private static byte[] syntheticSprite(boolean extra) {
        return syntheticSprite(extra, Opcodes.ACC_PRIVATE);
    }

    private static byte[] syntheticSprite(boolean extra, int interpolationAccess) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            AnimatedTextureAdapter.SPRITE, null, "java/lang/Object", null);
        MethodVisitor update = writer.visitMethod(Opcodes.ACC_PUBLIC,
            AnimatedTextureAdapter.UPDATE_SPRITE, "()V", null, null);
        update.visitCode();
        upload(update);
        if (extra) upload(update);
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();
        MethodVisitor interpolate = writer.visitMethod(interpolationAccess,
            AnimatedTextureAdapter.INTERPOLATE, "()V", null, null);
        interpolate.visitCode();
        upload(interpolate);
        interpolate.visitInsn(Opcodes.RETURN);
        interpolate.visitMaxs(0, 0);
        interpolate.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void upload(MethodVisitor method) {
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            AnimatedTextureAdapter.TEXTURE_UTIL, AnimatedTextureAdapter.UPLOAD,
            AnimatedTextureAdapter.UPLOAD_DESC, false);
    }

    private static boolean hasMethod(byte[] bytes, final String name,
                                     final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actual,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (name.equals(actual) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int calls(byte[] bytes, final String owner, final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String callOwner,
                                                          String callName,
                                                          String callDesc,
                                                          boolean itf) {
                        if (owner.equals(callOwner) && name.equals(callName)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] read(JarFile jar, String owner) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static byte[] patched(Method method, Object transformer, String name,
                                  String transformedName, byte[] original)
        throws Exception {
        byte[] result = (byte[]) method.invoke(transformer, name,
            transformedName, original);
        assertNotNull(result);
        new ClassReader(result);
        return result;
    }

    private static int callsInMethod(byte[] bytes, final String methodName,
                                     final String methodDescriptor,
                                     final String owner, final String callName,
                                     final String callDescriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                if (!methodName.equals(name)
                    || !methodDescriptor.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && callName.equals(actualName)
                            && callDescriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
