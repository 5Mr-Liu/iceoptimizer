package dev.rlcraft.ice.optimizer.render.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.model.ModelMeshCaptureBridge;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Locks the production ModelRenderer path to immediate, allocation-free IO. */
public final class ModelImmediatePathBytecodeTest {
    @Test
    public void runtimeDoesNotBuildPacketsForImmediatelyIssuedModels()
        throws Exception {
        final int[] directCalls = new int[1];
        final int[] packetCalls = new int[1];
        final int[] packetAllocations = new int[1];
        visit(ModernRendererRuntime.class, "tryDrawModelMesh",
            new MethodVisitor(Opcodes.ASM5) {
                @Override public void visitMethodInsn(int opcode, String owner,
                                                      String name,
                                                      String descriptor,
                                                      boolean itf) {
                    if (owner.equals(internal(LwjglModelMeshCache.class))
                        && "drawCurrent".equals(name)) directCalls[0]++;
                    if (owner.equals(internal(DrawPacket.class))
                        || owner.equals(internal(DrawPacketStream.class))) {
                        packetCalls[0]++;
                    }
                }

                @Override public void visitTypeInsn(int opcode, String type) {
                    if (opcode == Opcodes.NEW
                        && (type.equals(internal(DrawPacket.class))
                            || type.equals(internal(RenderStateKey.class)))) {
                        packetAllocations[0]++;
                    }
                }
            });
        assertEquals(1, directCalls[0]);
        assertEquals(0, packetCalls[0]);
        assertEquals(0, packetAllocations[0]);
    }

    @Test
    public void directCacheGateDoesNotSnapshotOrAllocate() throws Exception {
        final int[] allocations = new int[1];
        final int[] snapshots = new int[1];
        visit(LwjglModelMeshCache.class, "drawCurrent",
            new MethodVisitor(Opcodes.ASM5) {
                @Override public void visitTypeInsn(int opcode, String type) {
                    if (opcode == Opcodes.NEW || opcode == Opcodes.ANEWARRAY) {
                        allocations[0]++;
                    }
                }

                @Override public void visitIntInsn(int opcode, int operand) {
                    if (opcode == Opcodes.NEWARRAY) allocations[0]++;
                }

                @Override public void visitMultiANewArrayInsn(
                    String descriptor, int dimensions) {
                    allocations[0]++;
                }

                @Override public void visitMethodInsn(int opcode, String owner,
                                                      String name,
                                                      String descriptor,
                                                      boolean itf) {
                    if ("snapshot".equals(name)) snapshots[0]++;
                }
            });
        assertEquals(0, allocations[0]);
        assertEquals(0, snapshots[0]);
    }

    @Test
    public void arraySubmissionUsesPredecodedLayout() throws Exception {
        final int[] layoutWalks = new int[1];
        visit(LwjglModelMeshCache.class, "prepareArrays",
            new MethodVisitor(Opcodes.ASM5) {
                @Override public void visitMethodInsn(int opcode, String owner,
                                                      String name,
                                                      String descriptor,
                                                      boolean itf) {
                    if (owner.equals("net/minecraft/client/renderer/vertex/VertexFormat")
                        || owner.equals("java/util/List")) layoutWalks[0]++;
                }
            });
        assertEquals(0, layoutWalks[0]);
    }

    @Test
    public void clientArraysUseOneLegacyEquivalentSandboxRestorePath()
        throws Exception {
        final int[] pushes = new int[1];
        final int[] pops = new int[1];
        final int[] releases = new int[1];
        visit(LwjglModelMeshCache.class, "emitImmediate",
            new MethodVisitor(Opcodes.ASM5) {
                @Override public void visitMethodInsn(int opcode, String owner,
                                                      String name,
                                                      String descriptor,
                                                      boolean itf) {
                    if (owner.equals("org/lwjgl/opengl/GL11")) {
                        if ("glPushClientAttrib".equals(name)) pushes[0]++;
                        if ("glPopClientAttrib".equals(name)) pops[0]++;
                    }
                    if (owner.equals(internal(LwjglModelMeshCache.class))
                        && "releaseArrays".equals(name)) releases[0]++;
                }
            });
        assertEquals(1, pushes[0]);
        assertTrue("client attrib pop is missing", pops[0] > 0);
        assertEquals("normal path must not duplicate pop with array release",
            0, releases[0]);
    }

    @Test
    public void deferredUploadsRunAtFrameBoundaryNotAtCallListSite()
        throws Exception {
        final int[] callSiteAdmissions = new int[1];
        final int[] frameDrains = new int[1];
        final int[] drainAdmissions = new int[1];
        visit(ModelMeshCaptureBridge.class, "callList",
            admissions(callSiteAdmissions));
        visit(ModernRendererRuntime.class, "beginFrame",
            new MethodVisitor(Opcodes.ASM5) {
                @Override public void visitMethodInsn(int opcode, String owner,
                                                      String name,
                                                      String descriptor,
                                                      boolean itf) {
                    if (owner.equals(internal(ModelMeshCaptureBridge.class))
                        && "drainPendingModelMeshes".equals(name)) {
                        frameDrains[0]++;
                    }
                }
            });
        visit(ModelMeshCaptureBridge.class, "drainPendingModelMeshes",
            admissions(drainAdmissions));
        assertEquals(0, callSiteAdmissions[0]);
        assertEquals(1, frameDrains[0]);
        assertEquals(1, drainAdmissions[0]);
    }

    private static MethodVisitor admissions(final int[] count) {
        return new MethodVisitor(Opcodes.ASM5) {
            @Override public void visitMethodInsn(int opcode, String owner,
                                                  String name,
                                                  String descriptor,
                                                  boolean itf) {
                if (owner.equals(internal(ModelMeshCaptureBridge.class))
                    && "attemptPublication".equals(name)) count[0]++;
            }
        };
    }

    private static void visit(Class<?> type, final String selected,
                              final MethodVisitor visitor) throws Exception {
        String resource = "/" + internal(type) + ".class";
        InputStream input = type.getResourceAsStream(resource);
        if (input == null) throw new AssertionError("missing " + resource);
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override public MethodVisitor visitMethod(int access,
                                                           String name,
                                                           String descriptor,
                                                           String signature,
                                                           String[] exceptions) {
                    return selected.equals(name) ? visitor : null;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            input.close();
        }
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
