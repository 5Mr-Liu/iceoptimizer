package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class VanillaChunkRenderAdapterTest {
    private static final Sample[] SAMPLES = {
        new Sample("net.minecraft.client.renderer.chunk.ChunkRenderDispatcher",
            "8b0c19529e1c64cb9d38e411c2825c98d6b922a7ba90a095e1d11c4764164299"),
        new Sample("net.minecraft.client.renderer.BufferBuilder",
            "9dba42f97d33914a8fd0d11cfd296cf606c48d7a7d534d87f0fe983d7f1b5071"),
        new Sample("net.minecraft.client.renderer.vertex.VertexBuffer",
            "c82f374ecf6df5aa4fb53068180594002c939aab5691c7d44b030d7ea22c6526")
    };

    @Test
    public void transformsReviewedForgeSrgChunkPipeline() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            for (Sample sample : SAMPLES) {
                byte[] original = read(jar, sample.className);
                assertEquals(sample.sha256, CoreClassFingerprint.sha256(original));
                List<TargetSpec> targets = OptimizerTargetCatalog.findAll(sample.className);
                assertFalse(targets.isEmpty());
                byte[] transformed = original;
                for (TargetSpec target : targets) {
                    OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(target.adapterId);
                    assertTrue(adapter instanceof VanillaChunkRenderAdapter);
                    transformed = adapter.transform(sample.className, transformed, target);
                }
                assertFalse(Arrays.equals(original, transformed));
                new ClassReader(transformed);
                verify(sample.className, transformed);
                assertEquals(sample.className,
                    new ByteLoader(getClass().getClassLoader()).define(sample.className, transformed).getName());
            }
        } finally {
            jar.close();
        }
    }

    @Test
    public void dispatcherCatalogKeepsPolicyAndUploadAsIndependentCapabilities() {
        List<TargetSpec> targets = OptimizerTargetCatalog.findAll(
            "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher");
        assertEquals(2, targets.size());
        assertEquals("vanilla-chunk-dispatch-policy", targets.get(0).adapterId);
        assertEquals("vanilla-chunk-vbo-dispatch", targets.get(1).adapterId);
    }

    @Test
    public void transformerStillInstallsUploadWhenDispatcherPolicyShapeDiffers() {
        String className = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher";
        byte[] original = syntheticDispatcher(true, 0);
        assertFalse(OptimizerTargetCatalog.find(className)
            .accepts(CoreClassFingerprint.sha256(original)));

        byte[] transformed = new IceOptimizerTransformer().transform(
            className, className, original);

        assertFalse(Arrays.equals(original, transformed));
        assertEquals(0, countCalls(transformed, VanillaChunkRenderAdapter.POLICY_BRIDGE,
            "tuneWorkerCount", "(I)I"));
        assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.UPLOAD_BRIDGE,
            "tryUpload", "(Lnet/minecraft/client/renderer/BufferBuilder;"
                + "Lnet/minecraft/client/renderer/vertex/VertexBuffer;)Z"));
        assertTrue(hasMethod(transformed, VanillaChunkRenderAdapter.ORIGINAL_UPLOAD,
            VanillaChunkRenderAdapter.UPLOAD_DESCRIPTOR));
        new ClassReader(transformed);
    }

    @Test
    public void dispatcherPolicyToleratesHarmlessInstrumentationDistance() {
        String className = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher";
        byte[] transformed = new IceOptimizerTransformer().transform(
            className, className, syntheticDispatcher(false, 32));

        assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.POLICY_BRIDGE,
            "tuneWorkerCount", "(I)I"));
        assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.POLICY_BRIDGE,
            "clampBuilderCount", "(L" + VanillaChunkRenderAdapter.POLICY_ACCESS + ";)V"));
        assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.UPLOAD_BRIDGE,
            "tryUpload", "(Lnet/minecraft/client/renderer/BufferBuilder;"
                + "Lnet/minecraft/client/renderer/vertex/VertexBuffer;)Z"));
    }

    @Test
    public void dispatcherPolicyRunsAfterFermiumWorkerAndBuilderOverrides() {
        String className = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher";
        byte[] transformed = new IceOptimizerTransformer().transform(
            className, className, syntheticFermiumDispatcher());

        assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.POLICY_BRIDGE,
            "tuneWorkerCount", "(I)I"));
        assertTrue(callComesBefore(transformed, "<init>", className.replace('.', '/'),
            "normalasm$setWorkers", VanillaChunkRenderAdapter.POLICY_BRIDGE,
            "tuneWorkerCount"));
        assertEquals(1, countCallsInMethod(transformed, "normalasm$setBuilders",
            VanillaChunkRenderAdapter.POLICY_BRIDGE, "clampBuilderCount",
            "(L" + VanillaChunkRenderAdapter.POLICY_ACCESS + ";)V"));
    }

    private static void verify(String className, byte[] transformed) {
        if (className.endsWith("ChunkRenderDispatcher")) {
            assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.POLICY_BRIDGE,
                "tuneWorkerCount", "(I)I"));
            assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.POLICY_BRIDGE,
                "clampBuilderCount", "(L" + VanillaChunkRenderAdapter.POLICY_ACCESS + ";)V"));
            assertTrue(hasInterface(transformed, VanillaChunkRenderAdapter.POLICY_ACCESS));
            assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.UPLOAD_BRIDGE,
                "tryUpload", "(Lnet/minecraft/client/renderer/BufferBuilder;"
                    + "Lnet/minecraft/client/renderer/vertex/VertexBuffer;)Z"));
            assertTrue(hasMethod(transformed, VanillaChunkRenderAdapter.ORIGINAL_UPLOAD,
                VanillaChunkRenderAdapter.UPLOAD_DESCRIPTOR));
        } else if (className.endsWith("BufferBuilder")) {
            assertTrue(hasInterface(transformed, VanillaChunkRenderAdapter.BUFFER_ACCESS));
            assertTrue(hasMethod(transformed, VanillaChunkRenderAdapter.ORIGINAL_SORT,
                VanillaChunkRenderAdapter.SORT_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, VanillaChunkRenderAdapter.SORT_BRIDGE,
                "trySort", "(L" + VanillaChunkRenderAdapter.BUFFER_ACCESS + ";FFF)Z"));
            assertEquals(0, countCallsInMethod(transformed, VanillaChunkRenderAdapter.SORT_METHOD,
                "java/util/Arrays", "sort", "([Ljava/lang/Object;Ljava/util/Comparator;)V"));
        } else {
            assertTrue(hasInterface(transformed, VanillaChunkRenderAdapter.VBO_ACCESS));
            assertTrue(hasField(transformed, VanillaChunkRenderAdapter.CAPACITY_FIELD, "I"));
            assertTrue(hasMethod(transformed, VanillaChunkRenderAdapter.ORIGINAL_BUFFER_DATA,
                VanillaChunkRenderAdapter.BUFFER_DATA_DESCRIPTOR));
        }
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name, String signature,
                                        String superName, String[] interfaces) {
                if (interfaces != null) {
                    for (String value : interfaces) if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasMethod(byte[] bytes, final String expected,
                                     final String expectedDescriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (expected.equals(name) && expectedDescriptor.equals(descriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasField(byte[] bytes, final String expected,
                                    final String expectedDescriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor,
                                                     String signature, Object value) {
                if (expected.equals(name) && expectedDescriptor.equals(descriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name, final String descriptor) {
        return countCallsInMethod(bytes, null, owner, name, descriptor);
    }

    private static int countCallsInMethod(byte[] bytes, final String method,
                                          final String owner, final String name,
                                          final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actualMethod,
                                                       String actualDescriptor, String signature,
                                                       String[] exceptions) {
                if (method != null && !method.equals(actualMethod)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualCallDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualCallDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean callComesBefore(byte[] bytes, final String method,
                                           final String firstOwner, final String firstName,
                                           final String secondOwner, final String secondName) {
        final int[] ordinal = new int[1];
        final int[] first = { -1 };
        final int[] second = { -1 };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actualMethod,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                if (!method.equals(actualMethod)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String callDescriptor, boolean itf) {
                        int index = ordinal[0]++;
                        if (firstOwner.equals(owner) && firstName.equals(name)) first[0] = index;
                        if (secondOwner.equals(owner) && secondName.equals(name)) second[0] = index;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return first[0] >= 0 && second[0] > first[0];
    }

    private static byte[] syntheticDispatcher(boolean duplicateProcessorProbe, int paddingNops) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            VanillaChunkRenderAdapter.DISPATCHER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE,
            "field_188249_c", "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "<init>", "(I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
            "<init>", "()V", false);
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime",
            "getRuntime", "()Ljava/lang/Runtime;", false);
        constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime",
            "availableProcessors", "()I", false);
        for (int i = 0; i < paddingNops; i++) constructor.visitInsn(Opcodes.NOP);
        constructor.visitVarInsn(Opcodes.ISTORE, 2);
        if (duplicateProcessorProbe) {
            constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime",
                "getRuntime", "()Ljava/lang/Runtime;", false);
            constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime",
                "availableProcessors", "()I", false);
            constructor.visitInsn(Opcodes.POP);
        }
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitIntInsn(Opcodes.BIPUSH, 10);
        constructor.visitInsn(Opcodes.IMUL);
        constructor.visitFieldInsn(Opcodes.PUTFIELD,
            VanillaChunkRenderAdapter.DISPATCHER, "field_188249_c", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PUBLIC,
            VanillaChunkRenderAdapter.UPLOAD_METHOD,
            VanillaChunkRenderAdapter.UPLOAD_DESCRIPTOR, null, null);
        upload.visitCode();
        upload.visitTypeInsn(Opcodes.NEW,
            "net/minecraft/client/renderer/VertexBufferUploader");
        upload.visitInsn(Opcodes.DUP);
        upload.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/client/renderer/VertexBufferUploader", "<init>", "()V", false);
        upload.visitVarInsn(Opcodes.ASTORE, 3);
        upload.visitVarInsn(Opcodes.ALOAD, 3);
        upload.visitVarInsn(Opcodes.ALOAD, 2);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/VertexBufferUploader", "func_178178_a",
            "(Lnet/minecraft/client/renderer/vertex/VertexBuffer;)V", false);
        upload.visitVarInsn(Opcodes.ALOAD, 3);
        upload.visitVarInsn(Opcodes.ALOAD, 1);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/VertexBufferUploader", "func_181679_a",
            "(Lnet/minecraft/client/renderer/BufferBuilder;)V", false);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(0, 0);
        upload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticFermiumDispatcher() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            VanillaChunkRenderAdapter.DISPATCHER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE,
            "field_188249_c", "I", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "<init>", "(I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
            "<init>", "()V", false);
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime",
            "getRuntime", "()Ljava/lang/Runtime;", false);
        constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime",
            "availableProcessors", "()I", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitInsn(Opcodes.SWAP);
        constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            VanillaChunkRenderAdapter.DISPATCHER, "normalasm$setWorkers", "(I)I", false);
        constructor.visitVarInsn(Opcodes.ISTORE, 2);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            VanillaChunkRenderAdapter.DISPATCHER, "normalasm$setBuilders",
            "(L" + VanillaChunkRenderAdapter.DISPATCHER + ";I)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor workers = writer.visitMethod(Opcodes.ACC_PRIVATE,
            "normalasm$setWorkers", "(I)I", null, null);
        workers.visitCode();
        workers.visitIntInsn(Opcodes.BIPUSH, 10);
        workers.visitInsn(Opcodes.IRETURN);
        workers.visitMaxs(0, 0);
        workers.visitEnd();

        MethodVisitor builders = writer.visitMethod(Opcodes.ACC_PRIVATE,
            "normalasm$setBuilders", "(L" + VanillaChunkRenderAdapter.DISPATCHER
                + ";I)V", null, null);
        builders.visitCode();
        builders.visitVarInsn(Opcodes.ALOAD, 0);
        builders.visitIntInsn(Opcodes.BIPUSH, 100);
        builders.visitFieldInsn(Opcodes.PUTFIELD,
            VanillaChunkRenderAdapter.DISPATCHER, "field_188249_c", "I");
        builders.visitInsn(Opcodes.RETURN);
        builders.visitMaxs(0, 0);
        builders.visitEnd();

        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PUBLIC,
            VanillaChunkRenderAdapter.UPLOAD_METHOD,
            VanillaChunkRenderAdapter.UPLOAD_DESCRIPTOR, null, null);
        upload.visitCode();
        upload.visitTypeInsn(Opcodes.NEW,
            "net/minecraft/client/renderer/VertexBufferUploader");
        upload.visitInsn(Opcodes.DUP);
        upload.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/client/renderer/VertexBufferUploader", "<init>", "()V", false);
        upload.visitVarInsn(Opcodes.ASTORE, 3);
        upload.visitVarInsn(Opcodes.ALOAD, 3);
        upload.visitVarInsn(Opcodes.ALOAD, 2);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/VertexBufferUploader", "func_178178_a",
            "(Lnet/minecraft/client/renderer/vertex/VertexBuffer;)V", false);
        upload.visitVarInsn(Opcodes.ALOAD, 3);
        upload.visitVarInsn(Opcodes.ALOAD, 1);
        upload.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/VertexBufferUploader", "func_181679_a",
            "(Lnet/minecraft/client/renderer/BufferBuilder;)V", false);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(0, 0);
        upload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Sample {
        private final String className;
        private final String sha256;
        private Sample(String className, String sha256) {
            this.className = className;
            this.sha256 = sha256;
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
