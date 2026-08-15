package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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

public class XaeroTextureUploadAdapterTest {
    private static final String SYNTHETIC_INTERNAL = "dev/rlcraft/ice/hooks/SyntheticXaeroUploader";
    private static final String REAL_CLASS = "xaero.map.graphics.TextureUploader";
    private static final String REAL_ENTRY = "xaero/map/graphics/TextureUploader.class";

    @Test
    public void replacesOnlyTheReviewedSynchronousBenchmarkCalls() {
        byte[] original = syntheticClass(true);
        TargetSpec target = new TargetSpec(SYNTHETIC_INTERNAL.replace('/', '.'),
            "xaero-texture-upload,xaero-gpu-fence", "xaero-texture-batch", Collections.<String>emptySet());
        byte[] transformed = new XaeroTextureUploadAdapter().transform(target.className, original, target);

        assertFalse(Arrays.equals(original, transformed));
        assertEquals(8, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "isFinished"));
        assertEquals(6, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "getAverage"));
        assertEquals(1, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "beforeBatch"));
        assertEquals(1, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "begin"));
        assertEquals(1, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "end"));
        assertEquals(0, countCalls(transformed, "org/lwjgl/opengl/GL11", "glFinish"));
        new ClassReader(transformed);
    }

    @Test
    public void refusesAnyXaeroCallGraphDrift() {
        TargetSpec target = new TargetSpec(SYNTHETIC_INTERNAL.replace('/', '.'),
            "xaero-texture-upload,xaero-gpu-fence", "xaero-texture-batch", Collections.<String>emptySet());
        try {
            new XaeroTextureUploadAdapter().transform(target.className, syntheticClass(false), target);
            fail("adapter must reject a changed benchmark call graph");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("签名漂移"));
        }
    }

    @Test
    public void transformsTheConfiguredRealXaeroJarWhenAvailable() throws Exception {
        String configured = System.getProperty("ice.xaero.worldmap.jar", "").trim();
        Assume.assumeTrue("run with -PxaeroWorldMapJar=<jar> for the real-JAR integration test", !configured.isEmpty());
        File jarFile = new File(configured);
        Assume.assumeTrue("configured Xaero World Map JAR must exist", jarFile.isFile());

        byte[] original;
        JarFile jar = new JarFile(jarFile);
        try {
            JarEntry entry = jar.getJarEntry(REAL_ENTRY);
            assertTrue("reviewed Xaero uploader class must exist", entry != null);
            original = readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }

        byte[] transformed = new IceClientOptimizerTransformer().transform(REAL_CLASS, REAL_CLASS, original);
        assertFalse("the exact reviewed class hash must install the adapter", Arrays.equals(original, transformed));
        assertEquals(8, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "isFinished"));
        assertEquals(1, countCalls(transformed, XaeroTextureUploadAdapter.BRIDGE_OWNER, "beforeBatch"));
        assertEquals(0, countCalls(transformed, "org/lwjgl/opengl/GL11", "glFinish"));

        URLClassLoader dependencies = new URLClassLoader(new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            ByteLoader loader = new ByteLoader(dependencies);
            assertEquals(REAL_CLASS, loader.define(REAL_CLASS, transformed).getName());
        } finally {
            dependencies.close();
        }
    }

    private static byte[] syntheticClass(boolean exactGraph) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTHETIC_INTERNAL, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor request = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "requestUpload",
            "(Lxaero/map/graphics/TextureUploadBenchmark;)V", null, null);
        request.visitCode();
        int[] types = { 0, 2, 3, 4, 5, 6 };
        for (int type : types) {
            request.visitVarInsn(Opcodes.ALOAD, 0);
            pushInt(request, type);
            request.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XaeroTextureUploadAdapter.BENCHMARK_OWNER,
                "isFinished", "(I)Z", false);
            request.visitInsn(Opcodes.POP);
            request.visitVarInsn(Opcodes.ALOAD, 0);
            pushInt(request, type);
            request.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XaeroTextureUploadAdapter.BENCHMARK_OWNER,
                "getAverage", "(I)J", false);
            request.visitInsn(Opcodes.POP2);
        }
        request.visitInsn(Opcodes.RETURN);
        request.visitMaxs(2, 1);
        request.visitEnd();

        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PUBLIC, "uploadTextures", "()V", null, null);
        upload.visitCode();
        int loopChecks = exactGraph ? 2 : 1;
        for (int i = 0; i < loopChecks; i++) {
            upload.visitInsn(Opcodes.ACONST_NULL);
            upload.visitInsn(Opcodes.ICONST_0);
            upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XaeroTextureUploadAdapter.BENCHMARK_OWNER,
                "isFinished", "(I)Z", false);
            upload.visitInsn(Opcodes.POP);
        }
        upload.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFinish", "()V", false);
        upload.visitInsn(Opcodes.ACONST_NULL);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XaeroTextureUploadAdapter.BENCHMARK_OWNER, "pre", "()V", false);
        upload.visitInsn(Opcodes.ACONST_NULL);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XaeroTextureUploadAdapter.BENCHMARK_OWNER, "post", "(I)V", false);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(2, 5);
        upload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void pushInt(MethodVisitor visitor, int value) {
        if (value >= 0 && value <= 5) visitor.visitInsn(Opcodes.ICONST_0 + value);
        else visitor.visitIntInsn(Opcodes.BIPUSH, value);
    }

    private static int countCalls(byte[] bytes, final String expectedOwner, final String expectedName) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        if (expectedOwner.equals(owner) && expectedName.equals(name)) count[0]++;
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
