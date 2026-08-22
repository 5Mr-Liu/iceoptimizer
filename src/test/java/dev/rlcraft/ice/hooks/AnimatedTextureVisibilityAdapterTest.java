package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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

public final class AnimatedTextureVisibilityAdapterTest {
    private static final String CONTAINER =
        "net/minecraft/client/renderer/ChunkRenderContainer";
    private static final String TESSELLATOR =
        "net/minecraft/client/renderer/Tessellator";
    private static final String VBO_RENDER_LIST =
        "net/minecraft/client/renderer/VboRenderList";
    private static final String DISPLAY_RENDER_LIST =
        "net/minecraft/client/renderer/RenderList";
    private static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/AnimatedTextureVisibilityBootstrap";

    @Test
    public void insertsTheChunkVisibilityHookBeforeTheSingleListPublication() {
        byte[] transformed = transform(
            AnimatedTextureVisibilityAdapter.Part.CHUNK_CONTAINER,
            CONTAINER, container(false));
        assertEquals(1, calls(transformed, BOOTSTRAP, "terrainChunk"));
        assertEquals(1, calls(transformed, "java/util/List", "add"));
    }

    @Test
    public void insertsTheDynamicVisibilityHookBeforeTheOnlyBufferUpload() {
        byte[] transformed = transform(
            AnimatedTextureVisibilityAdapter.Part.TESSELLATOR,
            TESSELLATOR, tessellator(false));
        assertEquals(1, calls(transformed, BOOTSTRAP, "bufferDraw"));
        assertEquals(1, calls(transformed,
            "net/minecraft/client/renderer/WorldVertexBufferUploader",
            "func_181679_a"));
    }

    @Test
    public void insertsTheFinalVisibilityHookIntoBothTerrainEmitters() {
        byte[] vbo = transform(AnimatedTextureVisibilityAdapter.Part.CHUNK_DRAW,
            VBO_RENDER_LIST, renderList(VBO_RENDER_LIST));
        byte[] display = transform(
            AnimatedTextureVisibilityAdapter.Part.CHUNK_DRAW,
            DISPLAY_RENDER_LIST, renderList(DISPLAY_RENDER_LIST));
        assertEquals(1, calls(vbo, BOOTSTRAP, "terrainDraw"));
        assertEquals(1, calls(display, BOOTSTRAP, "terrainDraw"));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesAnAmbiguousChunkPublicationGraph() {
        transform(AnimatedTextureVisibilityAdapter.Part.CHUNK_CONTAINER,
            CONTAINER, container(true));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesAnAmbiguousDynamicUploadGraph() {
        transform(AnimatedTextureVisibilityAdapter.Part.TESSELLATOR,
            TESSELLATOR, tessellator(true));
    }

    @Test
    public void transformsBothProductionSrgBoundariesWhenProvided()
        throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar");
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>",
            configured != null && !configured.trim().isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            byte[] container = read(jar, CONTAINER);
            byte[] transformedContainer = transform(
                AnimatedTextureVisibilityAdapter.Part.CHUNK_CONTAINER,
                CONTAINER, container);
            assertFalse(Arrays.equals(container, transformedContainer));
            assertEquals(1, calls(transformedContainer, BOOTSTRAP,
                "terrainChunk"));

            byte[] vbo = read(jar, VBO_RENDER_LIST);
            byte[] transformedVbo = transform(
                AnimatedTextureVisibilityAdapter.Part.CHUNK_DRAW,
                VBO_RENDER_LIST, vbo);
            assertFalse(Arrays.equals(vbo, transformedVbo));
            assertEquals(1, calls(transformedVbo, BOOTSTRAP, "terrainDraw"));

            byte[] display = read(jar, DISPLAY_RENDER_LIST);
            byte[] transformedDisplay = transform(
                AnimatedTextureVisibilityAdapter.Part.CHUNK_DRAW,
                DISPLAY_RENDER_LIST, display);
            assertFalse(Arrays.equals(display, transformedDisplay));
            assertEquals(1, calls(transformedDisplay, BOOTSTRAP, "terrainDraw"));

            byte[] tessellator = read(jar, TESSELLATOR);
            byte[] transformedTessellator = transform(
                AnimatedTextureVisibilityAdapter.Part.TESSELLATOR,
                TESSELLATOR, tessellator);
            assertFalse(Arrays.equals(tessellator, transformedTessellator));
            assertEquals(1, calls(transformedTessellator, BOOTSTRAP,
                "bufferDraw"));
        } finally {
            jar.close();
        }
    }

    @Test
    public void reviewedOptifinePatchProvidesTheAnimatedSpriteVisibilityAbi()
        throws Exception {
        String optifine = System.getProperty("ice.optifine.jar", "").trim();
        String client = System.getProperty("ice.minecraft.client.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar and -PminecraftClientJar",
            !optifine.isEmpty() && !client.isEmpty());
        URLClassLoader loader = new URLClassLoader(
            new URL[] {new File(optifine).toURI().toURL(),
                new File(client).toURI().toURL()},
            AnimatedTextureVisibilityAdapterTest.class.getClassLoader());
        JarFile vanilla = new JarFile(new File(client));
        try {
            Class<?> transformerType = Class.forName(
                "optifine.OptiFineClassTransformer", true, loader);
            Object transformer = transformerType.newInstance();
            Method transform = transformerType.getMethod("transform",
                String.class, String.class, byte[].class);
            byte[] sprite = patched(transform, transformer, "cdq",
                "net.minecraft.client.renderer.texture.TextureAtlasSprite",
                read(vanilla, "cdq"));
            byte[] renderChunk = patched(transform, transformer, "bxr",
                "net.minecraft.client.renderer.chunk.RenderChunk",
                read(vanilla, "bxr"));
            byte[] compiled = patched(transform, transformer, "bxo",
                "net.minecraft.client.renderer.chunk.CompiledChunk",
                read(vanilla, "bxo"));
            byte[] buffer = patched(transform, transformer, "buk",
                "net.minecraft.client.renderer.BufferBuilder",
                read(vanilla, "buk"));
            String summary = "sprite=" + methodSummary(sprite) + " fields="
                + fieldSummary(sprite) + " renderChunk="
                + methodSummary(renderChunk) + " compiled="
                + methodSummary(compiled) + " fields=" + fieldSummary(compiled)
                + " buffer=" + methodSummary(buffer) + " fields="
                + fieldSummary(buffer);
            assertEquals(summary, 1,
                methods(sprite, "getIndexInMap", 0, "I"));
            // The notch name is remapped by Forge to func_178571_g, which the
            // production bridge also accepts.
            assertEquals(summary, 1, methods(renderChunk, "h", 0, "Lbxo;"));
            assertEquals(summary, 1, methods(compiled,
                "getAnimatedSprites", 1, "Ljava/util/BitSet;"));
            // G5 exposes the dynamic buffer set as a private field rather than
            // a getter; production uses an exact-type reflective accessor.
            assertEquals(summary, 1, fields(buffer, "animatedSprites",
                "Ljava/util/BitSet;"));
        } finally {
            vanilla.close();
            loader.close();
        }
    }

    private static byte[] transform(AnimatedTextureVisibilityAdapter.Part part,
                                    String owner, byte[] original) {
        byte[] transformed = new AnimatedTextureVisibilityAdapter(part)
            .transform(owner.replace('/', '.'), original,
                new TargetSpec(owner.replace('/', '.'),
                    "modern-texture-visibility", "test",
                    Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] container(boolean duplicateAdd) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CONTAINER, null,
            "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_178002_a", "(Lnet/minecraft/client/renderer/chunk/RenderChunk;"
                + "Lnet/minecraft/util/BlockRenderLayer;)V", null, null);
        method.visitCode();
        addToFreshList(method);
        if (duplicateAdd) addToFreshList(method);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addToFreshList(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList",
            "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
            "add", "(Ljava/lang/Object;)Z", true);
        method.visitInsn(Opcodes.POP);
    }

    private static byte[] tessellator(boolean duplicateUpload) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TESSELLATOR, null,
            "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_78381_a", "()V", null, null);
        method.visitCode();
        upload(method);
        if (duplicateUpload) upload(method);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] renderList(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null,
            CONTAINER, null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_178001_a", "(Lnet/minecraft/util/BlockRenderLayer;)V",
            null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void upload(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW,
            "net/minecraft/client/renderer/WorldVertexBufferUploader");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "net/minecraft/client/renderer/WorldVertexBufferUploader",
            "<init>", "()V", false);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/WorldVertexBufferUploader",
            "func_181679_a", "(Lnet/minecraft/client/renderer/BufferBuilder;)V",
            false);
    }

    private static int calls(byte[] bytes, final String owner,
                             final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access,
                String methodName, String descriptor, String signature,
                String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode,
                        String actualOwner, String actualName,
                        String actualDescriptor, boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int methods(byte[] bytes, final String name,
                               final int arguments,
                               final String returnDescriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access,
                String actualName, String descriptor, String signature,
                String[] exceptions) {
                if (name.equals(actualName)
                    && org.objectweb.asm.Type.getArgumentTypes(descriptor).length
                        == arguments
                    && (returnDescriptor == null || returnDescriptor.equals(
                        org.objectweb.asm.Type.getReturnType(descriptor)
                            .getDescriptor()))) {
                    count[0]++;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static String methodSummary(byte[] bytes) {
        final StringBuilder result = new StringBuilder();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                String descriptor, String signature, String[] exceptions) {
                if (result.length() != 0) result.append(',');
                result.append(name).append(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES);
        return result.toString();
    }

    private static String fieldSummary(byte[] bytes) {
        final StringBuilder result = new StringBuilder();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public org.objectweb.asm.FieldVisitor visitField(
                int access, String name, String descriptor, String signature,
                Object value) {
                if (result.length() != 0) result.append(',');
                result.append(name).append(':').append(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES);
        return result.toString();
    }

    private static int fields(byte[] bytes, final String name,
                              final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public org.objectweb.asm.FieldVisitor visitField(
                int access, String actualName, String actualDescriptor,
                String signature, Object value) {
                if (name.equals(actualName)
                    && descriptor.equals(actualDescriptor)) count[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] patched(Method method, Object transformer,
                                  String name, String transformedName,
                                  byte[] original) throws Exception {
        byte[] result = (byte[]) method.invoke(transformer, name,
            transformedName, original);
        assertNotNull(result);
        new ClassReader(result);
        return result;
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
}
