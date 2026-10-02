package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class ModelRendererVboAdapterTest {
    @Test
    public void realForgeModelClassesTransformAndVerify() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge SRG jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] renderer = new ModelRendererVboAdapter().transform(
                ModelRendererVboAdapter.TARGET, read(jar, ModelRendererVboAdapter.TARGET),
                target(ModelRendererVboAdapter.TARGET));
            assertEquals(4, calls(renderer, ModelRendererVboAdapter.BRIDGE, "callList"));
            assertEquals(1, calls(renderer, ModelRendererVboAdapter.BRIDGE, "begin"));
            assertEquals(1, calls(renderer, ModelRendererVboAdapter.BRIDGE, "finish"));
            assertEquals(1, calls(renderer, ModelRendererVboAdapter.BRIDGE, "cancel"));
            assertEquals(1, calls(renderer, ModelRendererVboAdapter.STATE_BATCH_BRIDGE, "begin"));
            assertEquals(1, calls(renderer, ModelRendererVboAdapter.STATE_BATCH_BRIDGE, "end"));
            assertEquals(1, calls(renderer, ModelRendererVboAdapter.STATE_BATCH_BRIDGE, "endAfterFailure"));
            assertTrue(classNode(renderer).interfaces.contains(ModelRendererVboAdapter.STATE_BATCH_ACCESS));
            verify(renderer);

            byte[] quad = new TexturedQuadCaptureAdapter().transform(
                TexturedQuadCaptureAdapter.TARGET,
                read(jar, TexturedQuadCaptureAdapter.TARGET),
                target(TexturedQuadCaptureAdapter.TARGET));
            assertEquals(1, calls(quad, TexturedQuadCaptureAdapter.BRIDGE,
                "captureQuad"));
            verify(quad);
        } finally {
            jar.close();
        }
    }

    @Test
    public void realOptifineG5RenderGraphGetsGuardedStateScope() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            byte[] original = support.patchAndRemap("brs", "net.minecraft.client.model.ModelRenderer");
            assertEquals(2, ModelStateBatchMethodProof.reviewedShape(classNode(original)));
            byte[] adapted = new ModelRendererVboAdapter().transform(
                ModelRendererVboAdapter.TARGET, original, target(ModelRendererVboAdapter.TARGET));
            assertTrue(classNode(adapted).interfaces.contains(ModelRendererVboAdapter.STATE_BATCH_ACCESS));
            assertEquals(1, calls(adapted, ModelRendererVboAdapter.STATE_BATCH_BRIDGE, "begin"));
            assertEquals(1, calls(adapted, ModelRendererVboAdapter.STATE_BATCH_BRIDGE, "isEmptyCanonicalList"));
            // The original CEM callback and texture branches remain intact.
            assertEquals(calls(original, "net/optifine/entity/model/anim/ModelUpdater", "update"),
                calls(adapted, "net/optifine/entity/model/anim/ModelUpdater", "update"));
            verify(adapted);
        }
    }

    @Test
    public void unreviewedRenderExtensionKeepsImmediateEmitterWithoutStateScope() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = classNode(support.patchAndRemap("brs", "net.minecraft.client.model.ModelRenderer"));
            MethodNode render = ModelStateBatchMethodProof.method(node, "func_78785_a", "(F)V");
            render.instructions.insert(new InsnNode(Opcodes.POP2));
            render.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/System", "nanoTime", "()J", false));
            assertEquals(0, ModelStateBatchMethodProof.reviewedShape(node));
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] adapted = new ModelRendererVboAdapter().transform(
                ModelRendererVboAdapter.TARGET, writer.toByteArray(), target(ModelRendererVboAdapter.TARGET));
            assertFalse(classNode(adapted).interfaces.contains(ModelRendererVboAdapter.STATE_BATCH_ACCESS));
            assertEquals(0, calls(adapted, ModelRendererVboAdapter.STATE_BATCH_BRIDGE, "begin"));
            assertEquals(4, calls(adapted, ModelRendererVboAdapter.BRIDGE, "callList"));
            verify(adapted);
        }
    }

    @Test
    public void changingOptifineResetHelperRejectsTheHeldStatePath() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = classNode(support.patchAndRemap("brs", "net.minecraft.client.model.ModelRenderer"));
            MethodNode reset = ModelStateBatchMethodProof.method(node, "checkResetDisplayList", "()V");
            reset.instructions.insert(new InsnNode(Opcodes.NOP));
            assertEquals(0, ModelStateBatchMethodProof.reviewedShape(node));
        }
    }

    private static ClassNode classNode(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    @Test
    public void realLlibraryAdvancedRendererTransformsAndVerifies() throws Exception {
        String configured = System.getProperty("ice.llibrary.jar", "").trim();
        Assume.assumeTrue("run with -PllibraryJar=<LLibrary jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] renderer = new AdvancedModelRendererVboAdapter().transform(
                AdvancedModelRendererVboAdapter.TARGET,
                read(jar, AdvancedModelRendererVboAdapter.TARGET),
                target(AdvancedModelRendererVboAdapter.TARGET));
            assertEquals(1, calls(renderer, AdvancedModelRendererVboAdapter.BRIDGE,
                "callList"));
            assertEquals(1, calls(renderer, AdvancedModelRendererVboAdapter.BRIDGE,
                "begin"));
            assertEquals(1, calls(renderer, AdvancedModelRendererVboAdapter.BRIDGE,
                "finish"));
            assertEquals(1, calls(renderer, AdvancedModelRendererVboAdapter.BRIDGE,
                "cancel"));
            verify(renderer);
        } finally {
            jar.close();
        }
    }

    private static TargetSpec target(String name) {
        return new TargetSpec(name, "modern-entity-backend,modern-tesr-backend",
            "test", Collections.<String>emptySet());
    }

    private static int calls(byte[] bytes, final String expectedOwner,
                             final String expectedName) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String descriptor,
                                                       String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String owner,
                                                          String name, String descriptor,
                                                          boolean itf) {
                        if (expectedOwner.equals(owner) && expectedName.equals(name)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
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
