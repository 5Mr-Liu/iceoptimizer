package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class RenderLibRendererAdapterTest {
    @Test
    public void wrapsOneEntityTraversalAndAllThreeSemanticEmitters() throws Exception {
        byte[] transformed = transform(RenderLibRendererAdapter.Part.ENTITY,
            RenderLibRendererAdapter.ENTITY, syntheticEntity());
        assertEquals(1, calls(transformed, "beginEntityTraversal"));
        assertEquals(1, calls(transformed, "renderEntity"));
        assertEquals(1, calls(transformed, "renderMultipass"));
        assertEquals(1, calls(transformed, "renderOutline"));
        assertEquals(1, calls(transformed, "endTraversal"));
        assertEquals(1, calls(transformed, "abortTraversal"));
        verify(transformed);
    }

    @Test
    public void wrapsOneTesrTraversalAndFinalDispatcherCall() throws Exception {
        byte[] transformed = transform(RenderLibRendererAdapter.Part.TESR,
            RenderLibRendererAdapter.TESR, syntheticTesr());
        assertEquals(1, calls(transformed, "beginTesrTraversal"));
        assertEquals(1, calls(transformed, "renderTileEntity"));
        verify(transformed);
    }

    @Test
    public void realRenderLib145EndpointsTransformAndVerify() throws Exception {
        String configured = System.getProperty("ice.dregora.renderlib.jar", "").trim();
        Assume.assumeTrue("run with -PdregoraRenderLibJar=<RenderLib jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            verify(transform(RenderLibRendererAdapter.Part.ENTITY,
                RenderLibRendererAdapter.ENTITY,
                read(jar, RenderLibRendererAdapter.ENTITY)));
            verify(transform(RenderLibRendererAdapter.Part.TESR,
                RenderLibRendererAdapter.TESR,
                read(jar, RenderLibRendererAdapter.TESR)));
        } finally {
            jar.close();
        }
    }

    private static byte[] transform(RenderLibRendererAdapter.Part part,
                                    String className, byte[] bytes) {
        return new RenderLibRendererAdapter(part).transform(className, bytes,
            new TargetSpec(className, part == RenderLibRendererAdapter.Part.ENTITY
                ? "modern-entity-backend" : "modern-tesr-backend", "test",
                Collections.<String>emptySet()));
    }

    private static byte[] syntheticEntity() {
        ClassWriter writer = base(RenderLibRendererAdapter.ENTITY);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PROTECTED,
            RenderLibRendererAdapter.ENTITY_METHOD,
            RenderLibRendererAdapter.ENTITY_DESCRIPTOR, null, null);
        method.visitCode();
        entityCall(method, "func_188388_a", true);
        entityCall(method, "func_188389_a", false);
        entityCall(method, "func_188388_a", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void entityCall(MethodVisitor method, String name, boolean flag) {
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitTypeInsn(Opcodes.CHECKCAST,
            "net/minecraft/client/renderer/entity/RenderManager");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/entity/Entity");
        method.visitVarInsn(Opcodes.FLOAD, 1);
        if (flag) method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/entity/RenderManager", name,
            flag ? "(Lnet/minecraft/entity/Entity;FZ)V"
                : "(Lnet/minecraft/entity/Entity;F)V", false);
    }

    private static byte[] syntheticTesr() {
        ClassWriter writer = base(RenderLibRendererAdapter.TESR);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PROTECTED,
            RenderLibRendererAdapter.TESR_METHOD,
            RenderLibRendererAdapter.TESR_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitTypeInsn(Opcodes.CHECKCAST,
            "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/tileentity/TileEntity");
        method.visitVarInsn(Opcodes.FLOAD, 1);
        method.visitInsn(Opcodes.ICONST_M1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher",
            "func_180546_a", "(Lnet/minecraft/tileentity/TileEntity;FI)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter base(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null,
            "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
            null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
            "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return writer;
    }

    private static int calls(byte[] bytes, final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String actual,
                                                          String descriptor,
                                                          boolean itf) {
                        if (RenderLibRendererAdapter.BRIDGE.equals(owner)
                            && name.equals(actual)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        assertTrue(!node.methods.isEmpty());
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        if (entry == null) throw new IllegalStateException("missing " + name);
        InputStream input = jar.getInputStream(entry);
        try {
            byte[] bytes = new byte[(int) entry.getSize()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IllegalStateException("truncated " + name);
                offset += count;
            }
            return bytes;
        } finally {
            input.close();
        }
    }
}
