package dev.rlcraft.ice.benchmark;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/** Opt-in diagnostic only. Measures complete methods after all mod transforms. */
final class RenderSectionInstrumenter {
    private static final String CLOCK = "dev/rlcraft/ice/benchmark/timing/RenderSectionClock";
    private static final boolean COARSE = Boolean.getBoolean("ice.bench.coarseSections");
    static byte[] transform(final String owner, byte[] source) {
        if (owner == null || !(owner.equals("net/minecraft/client/Minecraft")
            || owner.equals("net/minecraft/client/renderer/EntityRenderer")
            || owner.equals("net/minecraft/client/renderer/RenderGlobal")
            || owner.equals("net/minecraft/client/renderer/entity/RenderManager")
            || owner.equals("net/minecraft/client/model/ModelRenderer")
            || owner.equals("dev/rlcraft/ice/optimizer/compat/model/ModelStateBatchBridge")
            || owner.equals("dev/rlcraft/ice/optimizer/render/entity/LwjglModelMeshCache")
            || owner.equals("net/minecraftforge/client/GuiIngameForge")
            || owner.equals("net/minecraft/client/particle/ParticleManager")
            || owner.equals("net/minecraft/client/renderer/texture/TextureMap")
            || owner.equals("com/pg85/otg/forge/events/client/ClientFogHandler")
            || owner.equals("dev/rlcraft/ice/optimizer/client/ModernRendererRuntime")
            || owner.equals("dev/rlcraft/ice/optimizer/compat/renderlib/RenderLibRenderBridge")
            || owner.equals("dev/rlcraft/ice/optimizer/render/visibility/CurrentFrameTerrainOcclusion")
            || owner.equals("dev/rlcraft/ice/optimizer/compat/texture/AnimatedTextureVisibilityBridge")
            || owner.equals("dev/rlcraft/ice/optimizer/render/terrain/LwjglTerrainArena")
            || owner.equals("dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainRenderer")
            || owner.equals("dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainVisibility")
            || owner.equals("dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainVisibility$State")
            || owner.equals("dev/rlcraft/ice/optimizer/render/terrain/NativeChunkAnimation")
            || owner.equals("net/minecraft/client/renderer/chunk/ChunkRenderDispatcher")
            || owner.equals("dev/rlcraft/ice/optimizer/render/terrain/TerrainVertexIndexBuilder")
            || owner.equals("dev/rlcraft/ice/optimizer/render/arena/GpuArenaAllocator")
            || owner.equals("org/orecruncher/dsurround/client/renderer/weather/StormRenderer"))) return null;
        ClassReader reader = new ClassReader(source);
        final boolean[] sharedRegionOverload = {false};
        if (owner.endsWith("/LwjglTerrainArena")) reader.accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (name.equals("drawMappedRun") && (desc.endsWith("IZ)V") || desc.contains("$ElementBufferScope;"))) sharedRegionOverload[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, desc, sig, exceptions);
                if (owner.endsWith("/NativeTerrainVisibility$State") && name.equals("traverse"))
                    return visibilityDetails(visitor, access, name, desc);
                final int decision = owner.endsWith("/ModernRendererRuntime") && name.equals("modelStateBatchCandidate") ? 1
                    : owner.endsWith("/ModelStateBatchBridge") && name.equals("collect") ? 2
                    : owner.endsWith("/LwjglModelMeshCache") && name.equals("findStateBatchEntry") ? 3
                    : owner.endsWith("/LwjglModelMeshCache") && name.equals("beginStateBatch") && desc.equals("()Z") ? 4
                    : owner.endsWith("/ModelRenderer") && name.equals("ice$stateBatchNodeReady") ? 5 : -1;
                if (decision >= 0 && !COARSE) return new AdviceAdapter(Opcodes.ASM5, visitor, access, name, desc) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != IRETURN && opcode != ARETURN) return;
                        dup(); push(decision);
                        visitMethodInsn(INVOKESTATIC, CLOCK, opcode == ARETURN ? "objectDecision" : "decision",
                            opcode == ARETURN ? "(Ljava/lang/Object;I)V" : "(ZI)V", false);
                    }
                };
                if (!COARSE && owner.endsWith("/RenderLibRenderBridge") && name.equals("begin")) {
                    return new MethodVisitor(Opcodes.ASM5, visitor) {
                        @Override public void visitMethodInsn(int opcode, String calledOwner, String calledName, String descriptor, boolean itf) {
                            super.visitMethodInsn(opcode, calledOwner, calledName, descriptor, itf);
                            if (calledOwner.equals("dev/rlcraft/ice/optimizer/client/ModernRendererRuntime") && calledName.equals("shouldObserveBackend")) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, CLOCK, "filterBackendObservation", "(ZLjava/lang/Object;)Z", false);
                            }
                        }
                    };
                }
                // Since build18 the short mapped-run method delegates to the
                // overload. Time only its body, avoiding inclusive double counting.
                final int section = sharedRegionOverload[0] && name.equals("drawMappedRun")
                    && !desc.endsWith("IZ)V") && !desc.contains("$ElementBufferScope;") ? -1 : section(owner, name);
                // Per-mesh/per-model timers can dominate short native calls.
                // Keep only whole phases plus low-frequency upload/end-frame work.
                if (COARSE && section >= 15 && section < 30 && section != 25 && section != 26) return visitor;
                if (section < 0 || (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return visitor;
                return new AdviceAdapter(Opcodes.ASM5, visitor, access, name, desc) {
                    private int start;
                    private int submissionGlStart = -1;
                    private int callTimer = -1;
                    private int queueTimer = -1;
                    @Override protected void onMethodEnter() {
                        if (section == 14 && owner.endsWith("/LwjglTerrainArena")) {
                            loadThis(); loadArg(0); loadArg(1);
                            visitMethodInsn(INVOKESTATIC, "dev/rlcraft/ice/benchmark/TerrainSceneCapture", "capture", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                        visitMethodInsn(INVOKESTATIC, CLOCK, "begin", "()J", false);
                        start = newLocal(Type.LONG_TYPE);
                        storeLocal(start);
                        if (section == 36) {
                            callTimer = newLocal(Type.LONG_TYPE);
                            push(0L); storeLocal(callTimer);
                            queueTimer = newLocal(Type.LONG_TYPE);
                            push(0L); storeLocal(queueTimer);
                        }
                    }
                    @Override public void visitMethodInsn(int opcode, String calledOwner, String calledName, String descriptor, boolean itf) {
                        if (section == 36 && calledOwner.endsWith("/TerrainVisibilityAccessor") && calledName.equals("ice$renderInfos")) {
                            super.visitMethodInsn(INVOKESTATIC, CLOCK, "begin", "()J", false);
                            storeLocal(queueTimer);
                        }
                        if (section == 36 && calledOwner.endsWith("/NativeTerrainDrawQueue") && calledName.equals("animate")) {
                            push(47); loadLocal(queueTimer);
                            super.visitMethodInsn(INVOKESTATIC, CLOCK, "end", "(IJ)V", false);
                        }
                        int timed = owner.endsWith("/NativeTerrainRenderer") && name.equals("prepare")
                            ? calledOwner.equals("org/lwjgl/opengl/GL11") && calledName.equals("glGetFloat") ? 42
                            : calledOwner.endsWith("/NativeTerrainFrameData") && calledName.equals("pack") ? 43
                            : calledOwner.equals("java/nio/IntBuffer") && calledName.equals("put") ? 44
                            : calledOwner.endsWith("/TerrainCamera") && calledName.equals("eye") ? 45 : -1 : -1;
                        int callStart = -1;
                        if (timed >= 0) {
                            super.visitMethodInsn(INVOKESTATIC, CLOCK, "begin", "()J", false);
                            callStart = callTimer; storeLocal(callStart);
                        }
                        if (owner.endsWith("/NativeTerrainRenderer") && name.equals("prepare")
                            && submissionGlStart < 0 && calledOwner.equals("org/lwjgl/opengl/GL11")
                            && calledName.equals("glGetInteger")) {
                            push(34); loadLocal(start);
                            super.visitMethodInsn(INVOKESTATIC, CLOCK, "end", "(IJ)V", false);
                            super.visitMethodInsn(INVOKESTATIC, CLOCK, "begin", "()J", false);
                            submissionGlStart = newLocal(Type.LONG_TYPE);
                            storeLocal(submissionGlStart);
                        }
                        super.visitMethodInsn(opcode, calledOwner, calledName, descriptor, itf);
                        if (timed >= 0) {
                            push(timed); loadLocal(callStart);
                            super.visitMethodInsn(INVOKESTATIC, CLOCK, "end", "(IJ)V", false);
                        }
                    }
                    @Override protected void onMethodExit(int opcode) {
                        if (submissionGlStart >= 0) {
                            push(35); loadLocal(submissionGlStart);
                            visitMethodInsn(INVOKESTATIC, CLOCK, "end", "(IJ)V", false);
                        }
                        if (section == 15) {
                            loadArg(0); loadLocal(start);
                            visitMethodInsn(INVOKESTATIC, CLOCK, "endEntity", "(Ljava/lang/Object;J)V", false);
                        } else {
                            push(section); loadLocal(start);
                            visitMethodInsn(INVOKESTATIC, CLOCK, "end", "(IJ)V", false);
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static MethodVisitor visibilityDetails(MethodVisitor visitor, int access, String name, String desc) {
        return new AdviceAdapter(Opcodes.ASM5, visitor, access, name, desc) {
            private int timer;
            @Override protected void onMethodEnter() {
                super.visitMethodInsn(INVOKESTATIC, CLOCK, "visibilityDetailFrame", "()V", false);
                timer = newLocal(Type.LONG_TYPE);
                push(0L); storeLocal(timer);
            }
            @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean itf) {
                int section = owner.endsWith("/TerrainVisibilityAccessor")
                    ? called.equals("ice$appendRenderInfo") ? 48
                    : called.equals("ice$getRenderChunkOffset") ? 49
                    : called.equals("ice$isInFrustum") ? 50
                    : called.equals("ice$newRenderInfo") ? 52 : -1
                    : owner.endsWith("/TerrainRenderChunkIndexAccessor") && called.equals("ice$visibilityMask") ? 51
                    : owner.endsWith("/NativeTerrainDrawQueue") && called.equals("add") ? 53 : -1;
                if (section >= 0) {
                    super.visitMethodInsn(INVOKESTATIC, CLOCK, "beginVisibilityDetail", "()J", false);
                    storeLocal(timer);
                }
                super.visitMethodInsn(opcode, owner, called, descriptor, itf);
                if (section >= 0) {
                    push(section); loadLocal(timer);
                    super.visitMethodInsn(INVOKESTATIC, CLOCK, "end", "(IJ)V", false);
                }
            }
        };
    }
    private static int section(String owner, String name) {
        if (owner.endsWith("/Minecraft")) return name.equals("func_71411_J") ? 0 : name.equals("func_71407_l") ? 1 : -1;
        if (owner.endsWith("/EntityRenderer")) return name.equals("func_181560_a") ? 2 : name.equals("func_78474_d") ? 9 : -1;
        if (owner.endsWith("/RenderGlobal")) return name.equals("func_174970_a") ? 3 : name.equals("func_174977_a") ? 4 : name.equals("func_180446_a") ? 5 : name.equals("func_174967_a") ? 40 : -1;
        if (owner.endsWith("/GuiIngameForge")) return name.equals("func_175180_a") ? 6 : -1;
        if (owner.endsWith("/ParticleManager")) return name.equals("func_78874_a") ? 7 : -1;
        if (owner.endsWith("/ClientFogHandler")) return name.equals("onGetFogColor") ? 30 : name.equals("onRenderFog") ? 31 : -1;
        if (owner.endsWith("/TextureMap")) return name.equals("func_94248_c") ? 32 : -1;
        if (owner.endsWith("/NativeTerrainRenderer")) return name.equals("submit") ? 14 : name.equals("store") ? 25 : name.equals("prepare") ? 36 : name.equals("updateIndices") ? 46 : -1;
        if (owner.endsWith("/NativeTerrainVisibility")) return name.equals("traverse") ? 37 : -1;
        if (owner.endsWith("/NativeChunkAnimation")) return name.equals("offset") ? 41 : -1;
        if (owner.endsWith("/ChunkRenderDispatcher")) return name.equals("func_178516_a") ? 33 : name.equals("func_178505_b") ? 38 : name.equals("func_178507_a") ? 39 : -1;
        if (owner.endsWith("/StormRenderer")) return name.equals("render") ? 8 : -1;
        if (owner.endsWith("/ModernRendererRuntime")) return name.equals("beginFrame") ? 10 : name.equals("endFrame") ? 11
            : name.equals("resynchronizeHudState") ? 16 : name.equals("softwareTerrainMatrices") ? 17
            : name.equals("reauthenticateModelGlState") ? 18 : -1;
        if (owner.endsWith("/CurrentFrameTerrainOcclusion")) return name.equals("filterAdaptive") ? 12 : -1;
        if (owner.endsWith("/AnimatedTextureVisibilityBridge")) return name.equals("terrainDraw") ? 13 : -1;
        if (owner.endsWith("/LwjglTerrainArena")) return name.equals("render") ? 14
            : name.equals("directionStateSafe") ? 19 : name.equals("preserveArrayBufferBinding") ? 20
            : name.equals("drawMappedRun") ? 22 : name.equals("drawLegacyRun") ? 23
            : name.equals("drawMappedWithCompatibilityTransform") ? 24 : name.equals("upload") ? 25
            : name.equals("endFrame") ? 26 : name.equals("setupArrayPointers") ? 27
            : name.equals("drawMappedRegion") ? 28 : -1;
        if (owner.endsWith("/GpuArenaAllocator")) return name.equals("allocateFromFree") ? 21 : -1;
        if (owner.endsWith("/TerrainVertexIndexBuilder")) return name.equals("encode") ? 29 : -1;
        if (owner.endsWith("/RenderManager")) return name.equals("func_188391_a") ? 15 : -1;
        return -1;
    }
}
