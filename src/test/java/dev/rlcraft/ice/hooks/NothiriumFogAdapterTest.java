package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public class NothiriumFogAdapterTest {
    @Test public void unknownResourcesCannotBecomeHalfPatchedPairs() {
        try {
            NothiriumFogShaderRewrite.rewrite("#version 120\nvoid main(){}", "modified pack source");
            fail("Unknown pair must retain original semantics");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void actualDependencyAbiAndResourceSupplierLineEndings() throws Exception {
        String path = System.getProperty("ice.nothirium.jar");
        Assume.assumeNotNull(path);
        try (ZipFile jar = new ZipFile(path)) {
            String vertex = new String(read(jar, "assets/nothirium/shaders/chunk_vert.glsl"), StandardCharsets.UTF_8);
            String fragment = new String(read(jar, "assets/nothirium/shaders/chunk_frag.glsl"), StandardCharsets.UTF_8);
            String[] expected = NothiriumFogShaderRewrite.rewrite(vertex, fragment);
            assertArrayEquals(expected, NothiriumFogShaderRewrite.rewrite(vertex + "\r\n", fragment + "\r\n"));
            assertTrue(expected[0].contains("iceFogModelView"));
            assertFalse(expected[1].contains("fogValue * u_FogColor.a"));
            for (String suffix : new String[]{"renderer/chunk/ChunkRendererGL20", "renderer/chunk/ChunkRendererGL42",
                "renderer/chunk/ChunkRendererGL43", "util/FogUtil"}) {
                String name = "meldexun/nothirium/mc/" + suffix;
                byte[] transformed = new NothiriumFogAdapter().transform(name, read(jar, name + ".class"), null);
                ClassNode node = new ClassNode(Opcodes.ASM5);
                new ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES);
                int builds = 0, setups = 0, distances = 0;
                for (MethodNode method : node.methods) {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (instruction instanceof MethodInsnNode) {
                            MethodInsnNode call = (MethodInsnNode) instruction;
                            if (call.owner.equals("dev/rlcraft/ice/hooks/NothiriumFogBridge")) {
                                if (call.name.equals("build")) builds++;
                                if (call.name.equals("setup")) setups++;
                            }
                        }
                        if (instruction instanceof LdcInsnNode
                            && Double.valueOf(Double.MAX_VALUE).equals(((LdcInsnNode) instruction).cst)) distances++;
                    }
                }
                if (suffix.startsWith("renderer/")) {
                    assertEquals(name, 1, builds); assertEquals(name, 1, setups);
                } else assertEquals(1, distances);
            }
            assertTrue(NothiriumFogAdapter.isDistanceInstalled());
        }
    }

    private static byte[] read(ZipFile jar, String name) throws IOException {
        try (InputStream in = jar.getInputStream(jar.getEntry(name)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int n;
            while ((n = in.read(bytes)) >= 0) out.write(bytes, 0, n);
            return out.toByteArray();
        }
    }
}
