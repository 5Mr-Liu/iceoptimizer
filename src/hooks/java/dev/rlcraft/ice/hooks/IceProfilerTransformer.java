package dev.rlcraft.ice.hooks;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.ClassNode;

public final class IceProfilerTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("ICE Profiler Hooks");
    private static final Type BRIDGE_TYPE = Type.getObjectType(ProbeProtocol.BRIDGE_INTERNAL_NAME);
    private static final Set<String> TILE_ENTITY_INTERNALS = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> GENERATOR_INTERNALS = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || transformedName == null) return basicClass;
        try {
            ClassReader metadataReader = new ClassReader(basicClass);
            ClassNode metadata = new ClassNode();
            metadataReader.accept(metadata, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean tileEntity = identifyTileEntity(transformedName, metadata);
            boolean generator = identifyGenerator(metadata);
            TargetClass target = TargetClass.forName(transformedName, tileEntity, generator);
            if (target == TargetClass.NONE) return basicClass;

            final int[] patched = new int[1];
            ClassReader reader = new ClassReader(basicClass);
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
                private String owner;

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    owner = name;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor parent = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                    ProbeSpec spec = target.match(owner, access, methodName, descriptor);
                    if (spec == null) return parent;
                    patched[0]++;
                    return new ProbeAdvice(parent, access, methodName, descriptor, owner, spec);
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            if (patched[0] == 0) {
                LOGGER.warn("ICE hooks 跳过 {}：目标签名不存在，指纹 {}", transformedName, fingerprint(basicClass));
                return basicClass;
            }
            byte[] result = writer.toByteArray();
            LOGGER.info("ICE hooks 已为 {} 安装 {} 个只读计时点，原始指纹 {}", transformedName, patched[0], fingerprint(basicClass));
            return result;
        } catch (Throwable error) {
            LOGGER.error("ICE hooks 无法安全转换 " + transformedName + "，已保留原字节码（fail-open）", error);
            return basicClass;
        }
    }

    private static boolean identifyTileEntity(String transformedName, ClassNode node) {
        if ("net.minecraft.tileentity.TileEntity".equals(transformedName)) {
            TILE_ENTITY_INTERNALS.add(node.name);
            return true;
        }
        if (TILE_ENTITY_INTERNALS.contains(node.superName)) {
            TILE_ENTITY_INTERNALS.add(node.name);
            return true;
        }
        return false;
    }

    private static boolean identifyGenerator(ClassNode node) {
        if (GENERATOR_INTERNALS.contains(node.superName)) {
            GENERATOR_INTERNALS.add(node.name);
            return true;
        }
        for (Object value : node.interfaces) {
            String iface = String.valueOf(value);
            if (iface.endsWith("/IChunkGenerator") || "net/minecraft/world/gen/IChunkGenerator".equals(iface) || "axq".equals(iface)) {
                GENERATOR_INTERNALS.add(node.name);
                return true;
            }
        }
        return false;
    }

    private enum TargetClass {
        NONE {
            @Override ProbeSpec match(String owner, int access, String name, String desc) { return null; }
        },
        WORLD {
            @Override ProbeSpec match(String owner, int access, String name, String desc) {
                if (("updateEntityWithOptionalForce".equals(name) || "func_72866_a".equals(name) || "a".equals(name))
                    && (desc.endsWith(";Z)V"))) {
                    return new ProbeSpec(ProbeProtocol.ENTITY_TICK, Subject.ARG0, null);
                }
                return null;
            }
        },
        TILE_ENTITY {
            @Override ProbeSpec match(String owner, int access, String name, String desc) {
                return ("()V".equals(desc) && ("update".equals(name) || "func_73660_a".equals(name) || "e".equals(name)))
                    ? new ProbeSpec(ProbeProtocol.TILE_ENTITY_TICK, Subject.THIS, null) : null;
            }
        },
        GENERATOR {
            @Override ProbeSpec match(String owner, int access, String name, String desc) {
                if (("generateChunk".equals(name) || "func_185932_a".equals(name) || "a".equals(name)) && desc.startsWith("(II)L")) return new ProbeSpec(ProbeProtocol.CHUNK_GENERATION, Subject.THIS, null);
                if (("populate".equals(name) || "func_185931_b".equals(name) || "b".equals(name)) && "(II)V".equals(desc)) return new ProbeSpec(ProbeProtocol.CHUNK_GENERATION, Subject.THIS, null);
                return null;
            }
        },
        EVENT_HANDLER {
            @Override ProbeSpec match(String owner, int access, String name, String desc) {
                return "invoke".equals(name) && desc.endsWith(")V") ? new ProbeSpec(ProbeProtocol.EVENT_HANDLER, Subject.NAMED_FIELD, "readable") : null;
            }
        },
        CHUNK_SAVE {
            @Override ProbeSpec match(String owner, int access, String name, String desc) {
                boolean mapped = ("saveChunk".equals(name) || "func_75816_a".equals(name)) && desc.endsWith(")V");
                boolean obfuscated = "a".equals(name) && "(Lamu;Laxw;)V".equals(desc);
                return mapped || obfuscated ? new ProbeSpec(ProbeProtocol.CHUNK_SAVE, Subject.THIS, null) : null;
            }
        },
        CHUNK_RENDER {
            @Override ProbeSpec match(String owner, int access, String name, String desc) {
                boolean mapped = ("rebuildChunk".equals(name) || "func_178581_b".equals(name)) && desc.endsWith(")V");
                boolean obfuscated = "b".equals(name) && "(FFFLbxl;)V".equals(desc);
                return mapped || obfuscated ? new ProbeSpec(ProbeProtocol.CHUNK_RENDER, Subject.THIS, null) : null;
            }
        };

        abstract ProbeSpec match(String owner, int access, String name, String desc);

        static TargetClass forName(String transformedName, boolean tileEntity, boolean generator) {
            if ("net.minecraft.world.World".equals(transformedName)) return WORLD;
            if ("net.minecraftforge.fml.common.eventhandler.ASMEventHandler".equals(transformedName)) return EVENT_HANDLER;
            if ("net.minecraft.world.chunk.storage.AnvilChunkLoader".equals(transformedName)) return CHUNK_SAVE;
            if ("net.minecraft.client.renderer.chunk.RenderChunk".equals(transformedName)) return CHUNK_RENDER;
            if (tileEntity) return TILE_ENTITY;
            if (generator) return GENERATOR;
            return NONE;
        }
    }

    private enum Subject { THIS, ARG0, NAMED_FIELD }

    private static final class ProbeSpec {
        private final int id;
        private final Subject subject;
        private final String field;
        private ProbeSpec(int id, Subject subject, String field) { this.id = id; this.subject = subject; this.field = field; }
    }

    private static final class ProbeAdvice extends AdviceAdapter {
        private final String owner;
        private final ProbeSpec spec;
        private final Label protectedStart = new Label();
        private final Label protectedEnd = new Label();
        private final Label handler = new Label();
        private int tokenLocal;

        private ProbeAdvice(MethodVisitor visitor, int access, String name, String descriptor, String owner, ProbeSpec spec) {
            super(Opcodes.ASM5, visitor, access, name, descriptor);
            this.owner = owner;
            this.spec = spec;
        }

        @Override protected void onMethodEnter() {
            push(spec.id);
            if (spec.subject == Subject.ARG0) loadArg(0);
            else if (spec.subject == Subject.THIS) loadThis();
            else {
                loadThis();
                visitFieldInsn(GETFIELD, owner, spec.field, "Ljava/lang/String;");
            }
            if (spec.subject == Subject.NAMED_FIELD) invokeStatic(BRIDGE_TYPE, new org.objectweb.asm.commons.Method("enterNamed", "(ILjava/lang/String;)J"));
            else invokeStatic(BRIDGE_TYPE, new org.objectweb.asm.commons.Method("enter", "(ILjava/lang/Object;)J"));
            tokenLocal = newLocal(Type.LONG_TYPE);
            storeLocal(tokenLocal);
            visitLabel(protectedStart);
        }

        @Override protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) emitExit();
        }

        @Override public void visitMaxs(int maxStack, int maxLocals) {
            visitLabel(protectedEnd);
            visitTryCatchBlock(protectedStart, protectedEnd, handler, null);
            visitLabel(handler);
            int throwable = newLocal(Type.getType(Throwable.class));
            storeLocal(throwable);
            emitExit();
            loadLocal(throwable);
            throwException();
            super.visitMaxs(maxStack, maxLocals);
        }

        private void emitExit() {
            loadLocal(tokenLocal);
            invokeStatic(BRIDGE_TYPE, new org.objectweb.asm.commons.Method("exit", "(J)V"));
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) { return "java/lang/Object"; }
    }

    private static String fingerprint(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(16);
            for (int i = 0; i < 8; i++) value.append(String.format("%02x", digest[i] & 0xff));
            return value.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
