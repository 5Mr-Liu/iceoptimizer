package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ModernTerrainAdapterTest {
    @Test
    public void exposesOnlyTheRequiredContainerState() {
        byte[] transformed = transform(ModernTerrainAdapter.Part.CONTAINER_ACCESS,
            ModernTerrainAdapter.CONTAINER, syntheticContainer());
        assertTrue(hasInterface(transformed, ModernTerrainAdapter.ACCESS));
        assertTrue(hasMethod(transformed, "ice$renderChunks", "()Ljava/util/List;"));
        assertTrue(hasMethod(transformed, "ice$initialized", "()Z"));
        assertTrue(hasMethod(transformed, "ice$viewEntityX", "()D"));
        assertTrue(hasMethod(transformed, "ice$viewEntityY", "()D"));
        assertTrue(hasMethod(transformed, "ice$viewEntityZ", "()D"));
    }

    @Test
    public void resolvesReviewedOptifineFieldReuseFromThePositionWrites() {
        byte[] transformed = transform(ModernTerrainAdapter.Part.CONTAINER_ACCESS,
            ModernTerrainAdapter.CONTAINER, syntheticOptifineContainer());
        assertTrue(hasInterface(transformed, ModernTerrainAdapter.ACCESS));
        assertEquals("c", getterField(transformed, "ice$viewEntityX", "()D"));
        assertEquals("d", getterField(transformed, "ice$viewEntityY", "()D"));
        assertEquals("e", getterField(transformed, "ice$viewEntityZ", "()D"));
        assertEquals("a", getterField(transformed, "ice$renderChunks",
            "()Ljava/util/List;"));
        assertEquals("b", getterField(transformed, "ice$initialized", "()Z"));
    }

    @Test
    public void reviewedOptifinePostPatchContainerAcceptsTheProductionAdapter()
        throws Exception {
        OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip();
        try {
            byte[] remapped = support.patchAndRemap("bun",
                "net.minecraft.client.renderer.ChunkRenderContainer");
            byte[] transformed = transform(ModernTerrainAdapter.Part.CONTAINER_ACCESS,
                ModernTerrainAdapter.CONTAINER, remapped);
            assertTrue(hasInterface(transformed, ModernTerrainAdapter.ACCESS));
            assertEquals("field_178008_c",
                getterField(transformed, "ice$viewEntityX", "()D"));
            assertEquals("field_178005_d",
                getterField(transformed, "ice$viewEntityY", "()D"));
            assertEquals("field_178006_e",
                getterField(transformed, "ice$viewEntityZ", "()D"));
            assertEquals("field_178009_a", getterField(transformed,
                "ice$renderChunks", "()Ljava/util/List;"));
            assertEquals("field_178007_b",
                getterField(transformed, "ice$initialized", "()Z"));
        } finally {
            support.close();
        }
    }

    @Test
    public void wrapsTheFinalVboEmitterWithOneFailOpenDecision() {
        byte[] transformed = transform(ModernTerrainAdapter.Part.VBO_RENDER_LIST,
            ModernTerrainAdapter.VBO_RENDER_LIST, syntheticVboRenderList());
        assertTrue(hasMethod(transformed, ModernTerrainAdapter.ORIGINAL_RENDER,
            ModernTerrainAdapter.RENDER_LAYER_DESC));
        assertEquals(1, countCalls(transformed, ModernTerrainAdapter.RENDER_BRIDGE,
            "tryRender", "(Ljava/lang/Object;Lnet/minecraft/util/BlockRenderLayer;)Z"));
        assertEquals(1, countCalls(transformed, ModernTerrainAdapter.RENDER_BRIDGE,
            "beginRender", "(Lnet/minecraft/util/BlockRenderLayer;)J"));
        assertEquals(3, countCalls(transformed, ModernTerrainAdapter.RENDER_BRIDGE,
            "endRender", "(J)V"));
        assertEquals(1, countCallsInMethod(transformed, ModernTerrainAdapter.RENDER_LAYER,
            ModernTerrainAdapter.VBO_RENDER_LIST, ModernTerrainAdapter.ORIGINAL_RENDER,
            ModernTerrainAdapter.RENDER_LAYER_DESC));
        assertEquals(1, countCallsInMethod(transformed, ModernTerrainAdapter.RENDER_LAYER,
            ModernTerrainAdapter.RENDER_BRIDGE, "afterRender",
            "(Ljava/lang/Object;Lnet/minecraft/util/BlockRenderLayer;)V"));
    }

    @Test
    public void publishesUploadContextImmediatelyBeforeThePrivateUpload() {
        byte[] transformed = transform(ModernTerrainAdapter.Part.UPLOAD_CONTEXT,
            ModernTerrainAdapter.DISPATCHER, syntheticDispatcher());
        assertEquals(1, countCalls(transformed, ModernTerrainAdapter.UPLOAD_CONTEXT,
            "begin", "(Lnet/minecraft/util/BlockRenderLayer;"
                + "Lnet/minecraft/client/renderer/chunk/RenderChunk;"
                + "Lnet/minecraft/client/renderer/chunk/CompiledChunk;)V"));
        assertTrue(callBefore(transformed, ModernTerrainAdapter.UPLOAD_CHUNK,
            ModernTerrainAdapter.UPLOAD_CONTEXT, "begin", ModernTerrainAdapter.DISPATCHER,
            ModernTerrainAdapter.UPLOAD_VBO));
    }

    private static byte[] transform(ModernTerrainAdapter.Part part, String className,
                                    byte[] original) {
        ModernTerrainAdapter adapter = new ModernTerrainAdapter(part);
        byte[] transformed = adapter.transform(className, original,
            new TargetSpec(className, "modern-terrain-backend", "test",
                Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] syntheticContainer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            ModernTerrainAdapter.CONTAINER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "field_178009_a", "D", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_178007_b", "D", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_178008_c", "D", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PROTECTED, "field_178005_d", "Ljava/util/List;",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PROTECTED, "field_178006_e", "Z", null, null).visitEnd();
        positionSetter(writer, "field_178009_a", "field_178007_b", "field_178008_c");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticOptifineContainer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            ModernTerrainAdapter.CONTAINER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "c", "D", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "d", "D", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "e", "D", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PROTECTED, "a", "Ljava/util/List;",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PROTECTED, "b", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "animatedSpritesRendered",
            "Ljava/util/BitSet;", null, null).visitEnd();
        positionSetter(writer, "c", "d", "e");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void positionSetter(ClassWriter writer, String x, String y,
                                       String z) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "func_178004_a", "(DDD)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.DLOAD, 1);
        method.visitFieldInsn(Opcodes.PUTFIELD, ModernTerrainAdapter.CONTAINER, x, "D");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.DLOAD, 3);
        method.visitFieldInsn(Opcodes.PUTFIELD, ModernTerrainAdapter.CONTAINER, y, "D");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.DLOAD, 5);
        method.visitFieldInsn(Opcodes.PUTFIELD, ModernTerrainAdapter.CONTAINER, z, "D");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] syntheticVboRenderList() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ModernTerrainAdapter.VBO_RENDER_LIST,
            null, ModernTerrainAdapter.CONTAINER, null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            ModernTerrainAdapter.RENDER_LAYER, ModernTerrainAdapter.RENDER_LAYER_DESC,
            null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticDispatcher() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ModernTerrainAdapter.DISPATCHER,
            null, "java/lang/Object", null);
        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PUBLIC,
            ModernTerrainAdapter.UPLOAD_CHUNK, ModernTerrainAdapter.UPLOAD_CHUNK_DESC,
            null, null);
        upload.visitCode();
        upload.visitVarInsn(Opcodes.ALOAD, 0);
        upload.visitVarInsn(Opcodes.ALOAD, 2);
        upload.visitInsn(Opcodes.ACONST_NULL);
        upload.visitMethodInsn(Opcodes.INVOKESPECIAL, ModernTerrainAdapter.DISPATCHER,
            ModernTerrainAdapter.UPLOAD_VBO, ModernTerrainAdapter.UPLOAD_VBO_DESC, false);
        upload.visitInsn(Opcodes.ACONST_NULL);
        upload.visitInsn(Opcodes.ARETURN);
        upload.visitMaxs(0, 0);
        upload.visitEnd();
        MethodVisitor privateUpload = writer.visitMethod(Opcodes.ACC_PRIVATE,
            ModernTerrainAdapter.UPLOAD_VBO, ModernTerrainAdapter.UPLOAD_VBO_DESC,
            null, null);
        privateUpload.visitCode();
        privateUpload.visitInsn(Opcodes.RETURN);
        privateUpload.visitMaxs(0, 0);
        privateUpload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name,
                                        String signature, String superName,
                                        String[] interfaces) {
                if (interfaces != null) for (String value : interfaces) {
                    if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
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

    private static String getterField(byte[] bytes, final String name,
                                      final String descriptor) {
        final String[] found = new String[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actual,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (!name.equals(actual) || !descriptor.equals(desc)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitFieldInsn(int opcode, String owner,
                                                         String field, String fieldDesc) {
                        if (opcode == Opcodes.GETFIELD) found[0] = field;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countCalls(byte[] bytes, String owner, String name, String desc) {
        return countCallsInMethod(bytes, null, owner, name, desc);
    }

    private static int countCallsInMethod(byte[] bytes, final String selected,
                                          final String owner, final String name,
                                          final String desc) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                if (selected != null && !selected.equals(method)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualDesc, boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && desc.equals(actualDesc)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean callBefore(byte[] bytes, final String selected,
                                      final String firstOwner, final String firstName,
                                      final String secondOwner, final String secondName) {
        final int[] order = new int[1];
        final int[] first = { -1 };
        final int[] second = { -1 };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                if (!selected.equals(method)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String name, String desc,
                                                          boolean itf) {
                        int index = order[0]++;
                        if (firstOwner.equals(owner) && firstName.equals(name)) first[0] = index;
                        if (secondOwner.equals(owner) && secondName.equals(name)) second[0] = index;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return first[0] >= 0 && second[0] > first[0];
    }
}
