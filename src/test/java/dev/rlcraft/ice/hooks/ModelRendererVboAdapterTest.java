package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;

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
