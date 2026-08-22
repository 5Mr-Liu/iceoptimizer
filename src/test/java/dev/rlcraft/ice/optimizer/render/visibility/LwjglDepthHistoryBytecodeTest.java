package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class LwjglDepthHistoryBytecodeTest {
    @Test
    public void ordinaryCaptureAndConsumeNeverSynchronouslyQueryGlState() throws Exception {
        String resource = "/" + LwjglDepthHistory.class.getName().replace('.', '/') + ".class";
        InputStream input = LwjglDepthHistory.class.getResourceAsStream(resource);
        if (input == null) throw new AssertionError("missing " + resource);
        final int[] queries = new int[1];
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!"capture".equals(name)
                        && !"captureInternal".equals(name)
                        && !"preflightCapture".equals(name)
                        && !"consume".equals(name)
                        && !"ensureCapacity".equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitMethodInsn(int opcode, String owner,
                                                             String calledName,
                                                             String calledDescriptor,
                                                             boolean itf) {
                            if ("org/lwjgl/opengl/GL11".equals(owner)
                                && ("glGetInteger".equals(calledName)
                                    || "glGetFloat".equals(calledName)
                                    || "glGetBoolean".equals(calledName))) {
                                queries[0]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertEquals("ordinary HZB path must use the software mirror", 0, queries[0]);
    }

    @Test
    public void runtimeRejectsUnstableViewBeforeFallbackStateQuery()
        throws Exception {
        String resource = "/" + ModernRendererRuntime.class.getName()
            .replace('.', '/') + ".class";
        InputStream input = ModernRendererRuntime.class.getResourceAsStream(
            resource);
        if (input == null) throw new AssertionError("missing " + resource);
        final int[] order = new int[3];
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access,
                                                           String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!"afterTerrainLayer".equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitMethodInsn(int opcode,
                                                             String owner,
                                                             String calledName,
                                                             String calledDescriptor,
                                                             boolean itf) {
                            int position = ++order[0];
                            if (owner.equals(LwjglDepthHistory.class.getName()
                                .replace('.', '/'))
                                && "preflightCapture".equals(calledName)) {
                                order[1] = position;
                            }
                            if (owner.equals(ModernRendererRuntime.class
                                .getName().replace('.', '/'))
                                && "ensureHzbTrackedState".equals(calledName)) {
                                order[2] = position;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertTrue("missing HZB CPU preflight", order[1] > 0);
        assertTrue("GL state fallback query ran before stable-view preflight",
            order[2] > order[1]);
    }

    @Test
    public void perChunkProjectionPathDoesNotAllocateProjectionObjects() throws Exception {
        String owner = LwjglDepthHistory.class.getName().replace('.', '/') + "$DepthFrame";
        String resource = "/" + owner + ".class";
        InputStream input = LwjglDepthHistory.class.getResourceAsStream(resource);
        if (input == null) throw new AssertionError("missing " + resource);
        final int[] allocations = new int[1];
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!"project".equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.NEW
                                && (owner.substring(0, owner.length() - "DepthFrame".length())
                                    + "Projection").equals(type)) allocations[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertEquals("projection scratch must be reused", 0, allocations[0]);
    }

    @Test
    public void liveFilterBypassesAnimationsAndRequiresStableWitnesses()
        throws Exception {
        String resource = "/" + LwjglDepthHistory.class.getName()
            .replace('.', '/') + ".class";
        InputStream input = LwjglDepthHistory.class.getResourceAsStream(resource);
        if (input == null) throw new AssertionError("missing " + resource);
        final int[] calls = new int[2];
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access, String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!"filter".equals(name)) return null;
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
                            if (owner.equals(StableOcclusionGate.class.getName()
                                .replace('.', '/'))
                                && "confirm".equals(calledName)) calls[1]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertEquals("animated chunks must fail open", 1, calls[0]);
        assertEquals("raw HZB result reached the live list without history", 1,
            calls[1]);
    }

    @Test
    public void runtimeResolvesLiveFilterWithBothCommitAndRollbackPaths()
        throws Exception {
        String resource = "/" + ModernRendererRuntime.class.getName()
            .replace('.', '/') + ".class";
        InputStream input = ModernRendererRuntime.class.getResourceAsStream(
            resource);
        if (input == null) throw new AssertionError("missing " + resource);
        final int[] calls = new int[2];
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access,
                                                           String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    if (!"tryRenderTerrain".equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override public void visitMethodInsn(int opcode,
                                                             String owner,
                                                             String calledName,
                                                             String calledDescriptor,
                                                             boolean itf) {
                            if (!owner.equals(LwjglDepthHistory.class.getName()
                                .replace('.', '/'))) return;
                            if ("commitFilter".equals(calledName)) calls[0]++;
                            if ("rollbackFilter".equals(calledName)) calls[1]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
        assertTrue("arena-owned path never commits HZB", calls[0] >= 2);
        assertTrue("pre-submission path never restores legacy order",
            calls[1] >= 2);
    }
}
