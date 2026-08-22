package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import java.io.InputStream;
import net.minecraft.client.renderer.ChunkRenderContainer;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ChunkAnimatorTerrainCompatibilityBytecodeTest {
    @Test
    public void mixedArenaDrawsPreserveInjectedContainerTransforms()
        throws Exception {
        String resource = "/" + LwjglTerrainArena.class.getName()
            .replace('.', '/') + ".class";
        InputStream input = LwjglTerrainArena.class.getResourceAsStream(resource);
        if (input == null) throw new AssertionError("missing " + resource);
        final int[] calls = new int[3];
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!"render".equals(name)
                        && !"preRenderChunk".equals(name)
                        && !"drawMappedWithCompatibilityTransform".equals(name)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitMethodInsn(int opcode,
                                                             String owner,
                                                             String calledName,
                                                             String calledDescriptor,
                                                             boolean itf) {
                            if (owner.equals(ChunkAnimatorRenderBridge.class
                                .getName().replace('.', '/'))
                                && "requiresCompatibilityDraw".equals(calledName)) {
                                calls[0]++;
                            }
                            if (owner.equals(ChunkRenderContainer.class.getName()
                                .replace('.', '/'))
                                && "preRenderChunk".equals(calledName)) calls[1]++;
                            if (owner.equals(LwjglTerrainArena.class.getName()
                                .replace('.', '/'))
                                && "drawMappedWithCompatibilityTransform"
                                    .equals(calledName)) calls[2]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertTrue("arena grouping ignored pending chunk animations",
            calls[0] >= 2);
        assertTrue("compatibility draw bypassed transformed preRenderChunk",
            calls[1] >= 1);
        assertTrue("animated arena mesh was not split from the batch",
            calls[2] >= 1);
    }
}
