package dev.rlcraft.ice.optimizer.render;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Prevents accidental driver-synchronizing queries in ordinary frame paths. */
public final class OrdinaryRenderPathBytecodeTest {
    @Test
    public void ordinaryUploadAndSubmissionMethodsHaveNoDirectGlGetCalls()
        throws Exception {
        assertNoQueries(LwjglTerrainArena.class, "upload", "write", "render",
            "drawMappedRun", "drawIndirect", "ensureCommandCapacity",
            "ensureIndirectCommandCapacity");
        assertNoQueries(LwjglAnimatedTextureUploadStream.class, "flush",
            "upload", "directUpload");
        assertNoQueries(LwjglParticleRenderer.class, "flush", "acquireSlot");
        assertNoQueries(LwjglHudRenderer.class, "flush", "draw");
    }

    private static void assertNoQueries(Class<?> type, String... methodNames)
        throws Exception {
        final Set<String> selected = new HashSet<String>(Arrays.asList(methodNames));
        final int[] queries = new int[1];
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        InputStream input = type.getResourceAsStream(resource);
        if (input == null) throw new AssertionError("missing " + resource);
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!selected.contains(name)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitMethodInsn(int opcode, String owner,
                                                             String calledName,
                                                             String descriptor,
                                                             boolean itf) {
                            if (owner.startsWith("org/lwjgl/opengl/")
                                && (calledName.startsWith("glGet")
                                    || "glFinish".equals(calledName)
                                    || "glReadPixels".equals(calledName))) {
                                queries[0]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertEquals(type.getSimpleName() + " ordinary path synchronized GL", 0,
            queries[0]);
    }
}
