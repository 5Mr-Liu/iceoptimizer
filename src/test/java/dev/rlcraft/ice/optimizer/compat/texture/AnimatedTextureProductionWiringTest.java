package dev.rlcraft.ice.optimizer.compat.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Verifies that explicit-sprite production emitters mark visibility first. */
public final class AnimatedTextureProductionWiringTest {
    private static final String VISIBILITY =
        "dev/rlcraft/ice/optimizer/compat/texture/AnimatedTextureVisibilityBridge";

    @Test
    public void hudSpriteCatchesUpBeforeRecordingOrFallingBack() throws Exception {
        Calls calls = inspect(
            "dev/rlcraft/ice/optimizer/compat/hud/HudRenderBridge",
            "tryTexturedSprite");
        assertEquals(1, calls.visibilityCount);
        assertTrue(calls.visibilityOrder >= 0);
        assertTrue(calls.recordOrder > calls.visibilityOrder);
    }

    @Test
    public void particleSpriteCatchesUpBeforeEitherEmitter() throws Exception {
        Calls calls = inspect(
            "dev/rlcraft/ice/optimizer/compat/particle/ParticleRenderBridge",
            "render");
        assertEquals(1, calls.spriteGetterCount);
        assertEquals(1, calls.visibilityCount);
        assertTrue(calls.spriteGetterOrder >= 0);
        assertTrue(calls.visibilityOrder > calls.spriteGetterOrder);
        assertTrue(calls.firstEmitterOrder > calls.visibilityOrder);
    }

    private static Calls inspect(String owner, final String selected)
        throws Exception {
        final Calls calls = new Calls();
        byte[] bytes = read(owner);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                String descriptor, String signature, String[] exceptions) {
                if (!selected.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode,
                        String actualOwner, String actualName,
                        String actualDescriptor, boolean itf) {
                        int order = calls.nextOrder++;
                        if (VISIBILITY.equals(actualOwner)
                            && "spriteVisible".equals(actualName)) {
                            calls.visibilityCount++;
                            calls.visibilityOrder = order;
                        }
                        if ("ice$particleTexture".equals(actualName)) {
                            calls.spriteGetterCount++;
                            calls.spriteGetterOrder = order;
                        }
                        if ("recordQuad".equals(actualName)) {
                            calls.recordOrder = order;
                        }
                        if (calls.firstEmitterOrder < 0
                            && ("renderParticle".equals(actualName)
                                || "emitToRenderer".equals(actualName))) {
                            calls.firstEmitterOrder = order;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    private static byte[] read(String owner) throws Exception {
        InputStream input = AnimatedTextureProductionWiringTest.class
            .getClassLoader().getResourceAsStream(owner + ".class");
        if (input == null) throw new AssertionError("missing " + owner);
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

    private static final class Calls {
        private int nextOrder;
        private int visibilityCount;
        private int visibilityOrder = -1;
        private int spriteGetterCount;
        private int spriteGetterOrder = -1;
        private int recordOrder = -1;
        private int firstEmitterOrder = -1;
    }
}
