package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

public class FoamFixTextureUploadAdapterTest {
    private static final String SYNTHETIC_INTERNAL = "dev/rlcraft/ice/hooks/SyntheticFoamFixSprite";
    private static final String REAL_CLASS = "pl.asie.foamfix.client.FastTextureAtlasSprite";
    private static final String REAL_ENTRY = "pl/asie/foamfix/client/FastTextureAtlasSprite.class";

    @Test
    public void injectsOneGuardAndPreservesTheOriginalFallbackBody() throws Exception {
        byte[] original = syntheticClass(true);
        TargetSpec target = new TargetSpec(SYNTHETIC_INTERNAL.replace('/', '.'), "foamfix-texture-upload",
            "foamfix-pbo-upload", Collections.<String>emptySet());
        byte[] transformed = new FoamFixTextureUploadAdapter().transform(target.className, original, target);

        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countBridgeCalls(transformed));
        new ClassReader(transformed);

        ByteLoader loader = new ByteLoader(getClass().getClassLoader());
        Class<?> sprite = loader.define(SYNTHETIC_INTERNAL.replace('/', '.'), transformed);
        Method method = sprite.getDeclaredMethod(FoamFixTextureUploadAdapter.TARGET_METHOD,
            int.class, int[][].class, int.class, int.class, int.class, int.class,
            boolean.class, boolean.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, new Object[] { 0, null, 1, 1, 0, 0, false, false, false });
        assertEquals("bridge false path must execute the untouched FoamFix body", 37,
            sprite.getField("fallbackMarker").getInt(null));
    }

    @Test
    public void transformedClassStartsWithOnlyTheCoreBridgeVisible() throws Exception {
        byte[] original = syntheticClass(true);
        TargetSpec target = new TargetSpec(SYNTHETIC_INTERNAL.replace('/', '.'),
            "foamfix-texture-upload", "foamfix-pbo-upload",
            Collections.<String>emptySet());
        byte[] transformed = new FoamFixTextureUploadAdapter().transform(
            target.className, original, target);

        URL coreOutput = TextureUploadBootstrap.class.getProtectionDomain()
            .getCodeSource().getLocation();
        URLClassLoader coreOnly = new URLClassLoader(new URL[] { coreOutput }, null);
        try {
            ByteLoader loader = new ByteLoader(coreOnly);
            Class<?> sprite = loader.define(SYNTHETIC_INTERNAL.replace('/', '.'), transformed);
            Method method = sprite.getDeclaredMethod(FoamFixTextureUploadAdapter.TARGET_METHOD,
                int.class, int[][].class, int.class, int.class, int.class, int.class,
                boolean.class, boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(null, new Object[] {
                0, null, 1, 1, 0, 0, false, false, false
            });
            assertEquals("early Core-only loading must retain the untouched FoamFix body", 37,
                sprite.getField("fallbackMarker").getInt(null));
        } finally {
            coreOnly.close();
        }
    }

    @Test
    public void refusesAClassWithoutTheExactReviewedMethodDescriptor() {
        TargetSpec target = new TargetSpec(SYNTHETIC_INTERNAL.replace('/', '.'), "foamfix-texture-upload",
            "foamfix-pbo-upload", Collections.<String>emptySet());
        try {
            new FoamFixTextureUploadAdapter().transform(target.className, syntheticClass(false), target);
            fail("adapter must reject a signature drift");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("匹配数量"));
        }
    }

    @Test
    public void transformsTheConfiguredRealFoamFixJarWhenAvailable() throws Exception {
        String configured = System.getProperty("ice.foamfix.jar", "").trim();
        Assume.assumeTrue("run with -PfoamfixJar=<jar> for the real-JAR integration test", !configured.isEmpty());
        File jarFile = new File(configured);
        Assume.assumeTrue("configured FoamFix JAR must exist", jarFile.isFile());

        byte[] original;
        JarFile jar = new JarFile(jarFile);
        try {
            JarEntry entry = jar.getJarEntry(REAL_ENTRY);
            assertTrue("reviewed FoamFix class must exist", entry != null);
            original = readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }

        byte[] transformed = new IceClientOptimizerTransformer().transform(REAL_CLASS, REAL_CLASS, original);
        assertFalse("the exact reviewed class hash must install the adapter", Arrays.equals(original, transformed));
        assertEquals(1, countBridgeCalls(transformed));
        new ClassReader(transformed);

        URLClassLoader dependencies = new URLClassLoader(new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            ByteLoader loader = new ByteLoader(dependencies);
            assertEquals(REAL_CLASS, loader.define(REAL_CLASS, transformed).getName());
        } finally {
            dependencies.close();
        }
    }

    private static byte[] syntheticClass(boolean exactDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTHETIC_INTERNAL, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fallbackMarker", "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        String descriptor = exactDescriptor ? FoamFixTextureUploadAdapter.TARGET_DESCRIPTOR : "(I[[IIIIZZZ)V";
        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            FoamFixTextureUploadAdapter.TARGET_METHOD, descriptor, null, null);
        upload.visitCode();
        upload.visitIntInsn(Opcodes.BIPUSH, 37);
        upload.visitFieldInsn(Opcodes.PUTSTATIC, SYNTHETIC_INTERNAL, "fallbackMarker", "I");
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(1, 9);
        upload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countBridgeCalls(byte[] bytes) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && FoamFixTextureUploadAdapter.BRIDGE_OWNER.equals(owner)
                            && FoamFixTextureUploadAdapter.BRIDGE_METHOD.equals(name)
                            && FoamFixTextureUploadAdapter.BRIDGE_DESCRIPTOR.equals(descriptor)) {
                            count[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static byte[] readFully(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
