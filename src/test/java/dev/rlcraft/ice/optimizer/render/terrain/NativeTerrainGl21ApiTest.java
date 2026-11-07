package dev.rlcraft.ice.optimizer.render.terrain;

import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

/** Prevent a later refactor from silently reintroducing GL 3+ requirements. */
public class NativeTerrainGl21ApiTest {
    @Test public void compatibilityOperationsStayWithinOpenGl21() throws Exception {
        for (String simple : new String[] {"NativeTerrainGl21", "NativeTerrainPages21",
                "NativeTerrainTextureState", "NativeTerrainPausedMaterials", "NativeTerrainBackend", "NativeTerrainProgram", "NativeTerrainRenderer",
                "NativeTerrainShaderPack"}) {
            final String checked = simple;
            new ClassReader("dev.rlcraft.ice.optimizer.render.terrain." + simple).accept(
                new ClassVisitor(Opcodes.ASM5) {
                    @Override public MethodVisitor visitMethod(int a, final String name, String d, String s, String[] e) {
                        if (checked.equals("NativeTerrainShaderPack")
                            && !name.equals("setupArrays21") && !name.equals("enableArrays21")) return null;
                        if (checked.equals("NativeTerrainStorage") && !name.equals("<init>") && !name.equals("delete")) return null;
                        return new MethodVisitor(Opcodes.ASM5) {
                            @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                                if (!owner.startsWith("org/lwjgl/opengl/")) return;
                                assertTrue(checked + "." + name + " calls " + owner + "." + method,
                                    owner.matches("org/lwjgl/opengl/(GL1[1-5]|GL2[01]|GLContext)"));
                                assertFalse("No buffer readback in the compatibility path", method.equals("glGetBufferSubData"));
                                assertFalse("No per-frame capability probes", owner.endsWith("GLContext"));
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }
}
