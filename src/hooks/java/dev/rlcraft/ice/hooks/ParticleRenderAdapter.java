package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Structural ParticleManager final-emitter and vanilla Particle field ABI. */
final class ParticleRenderAdapter implements OptimizerBytecodeAdapter {
    enum Part { MANAGER, PARTICLE_ACCESS }

    static final String MANAGER = "net/minecraft/client/particle/ParticleManager";
    static final String PARTICLE = "net/minecraft/client/particle/Particle";
    static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder";
    static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    static final String VERTEX_FORMAT =
        "net/minecraft/client/renderer/vertex/VertexFormat";
    static final String ENTITY = "net/minecraft/entity/Entity";
    static final String TEXTURE =
        "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/particle/ParticleRenderBridge";
    static final String ACCESS =
        "dev/rlcraft/ice/optimizer/compat/particle/ParticleRenderAccess";
    static final String RENDER_MANAGER = "func_78874_a";
    static final String RENDER_MANAGER_DESC = "(L" + ENTITY + ";F)V";
    static final String RENDER_LIT = "func_78872_b";
    static final String RENDER_LIT_DESC = RENDER_MANAGER_DESC;
    static final String RENDER_PARTICLE = "func_180434_a";
    static final String RENDER_PARTICLE_DESC = "(L" + BUFFER + ";L" + ENTITY
        + ";FFFFFF)V";
    static final String ORIGINAL = "ice$renderParticlesOriginal";
    static final String ORIGINAL_LIT = "ice$renderLitParticlesOriginal";

    private final Part part;

    ParticleRenderAdapter(Part part) {
        if (part == null) throw new IllegalArgumentException("part");
        this.part = part;
    }

    @Override
    public byte[] transform(String transformedName, byte[] originalClass,
                            TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (part == Part.MANAGER) transformManager(node);
        else transformParticle(node);
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void transformManager(ClassNode node) {
        requireClass(node, MANAGER);
        if (findMethod(node, ORIGINAL, RENDER_MANAGER_DESC) != null) {
            throw new IllegalStateException("ParticleManager already adapted");
        }
        MethodNode render = requireMethod(node, RENDER_MANAGER, RENDER_MANAGER_DESC);
        MethodInsnNode selected = null;
        int calls = 0;
        int begins = 0;
        int draws = 0;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && PARTICLE.equals(call.owner)
                && RENDER_PARTICLE.equals(call.name)
                && RENDER_PARTICLE_DESC.equals(call.desc)) {
                selected = call;
                calls++;
            } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && BUFFER.equals(call.owner) && "func_181668_a".equals(call.name)
                && ("(IL" + VERTEX_FORMAT + ";)V").equals(call.desc)) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = "beginBuffer";
                call.desc = "(L" + BUFFER + ";IL" + VERTEX_FORMAT + ";)V";
                call.itf = false;
                begins++;
            } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && TESSELLATOR.equals(call.owner)
                && "func_78381_a".equals(call.name) && "()V".equals(call.desc)) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = BRIDGE;
                call.name = "draw";
                call.desc = "(L" + TESSELLATOR + ";)V";
                call.itf = false;
                draws++;
            }
        }
        if (calls != 1 || begins != 1 || draws != 1 || selected == null
            || (render.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("ParticleManager emitter graph changed: render="
                + calls + ", begin=" + begins + ", draw=" + draws);
        }
        selected.setOpcode(Opcodes.INVOKESTATIC);
        selected.owner = BRIDGE;
        selected.name = "render";
        selected.desc = "(L" + PARTICLE + ";" + RENDER_PARTICLE_DESC.substring(1);
        selected.itf = false;

        int access = render.access;
        String signature = render.signature;
        List<String> exceptions = render.exceptions;
        render.name = ORIGINAL;
        render.access = (render.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(managerWrapper(access, signature, exceptions));
        wrapLitParticles(node);
    }

    private static void wrapLitParticles(ClassNode node) {
        if (findMethod(node, ORIGINAL_LIT, RENDER_LIT_DESC) != null) {
            throw new IllegalStateException("ParticleManager lit pass already adapted");
        }
        MethodNode render = requireMethod(node, RENDER_LIT, RENDER_LIT_DESC);
        if ((render.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
            | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("ParticleManager lit emitter changed");
        }
        int access = render.access;
        String signature = render.signature;
        List<String> exceptions = render.exceptions;
        render.name = ORIGINAL_LIT;
        render.access = (render.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        node.methods.add(litWrapper(access, signature, exceptions));
    }

    private static MethodNode litWrapper(int access, String signature,
                                         List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, RENDER_LIT,
            RENDER_LIT_DESC, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "beginLit", "()J", false);
        code.visitVarInsn(Opcodes.LSTORE, 3);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.FLOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, MANAGER, ORIGINAL_LIT,
            RENDER_LIT_DESC, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "endLit", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 5);
        code.visitVarInsn(Opcodes.LLOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "endLit", "(J)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 5);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static MethodNode managerWrapper(int access, String signature,
                                             List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, RENDER_MANAGER,
            RENDER_MANAGER_DESC, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.FLOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin",
            "(Ljava/lang/Object;L" + ENTITY + ";F)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 3);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitVarInsn(Opcodes.FLOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, MANAGER, ORIGINAL,
            RENDER_MANAGER_DESC, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 3);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "end", "(J)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 5);
        code.visitVarInsn(Opcodes.LLOAD, 3);
        code.visitVarInsn(Opcodes.ALOAD, 5);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "abort",
            "(JLjava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 5);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static void transformParticle(ClassNode node) {
        requireClass(node, PARTICLE);
        requireMethod(node, RENDER_PARTICLE, RENDER_PARTICLE_DESC);
        if (node.interfaces.contains(ACCESS)) {
            throw new IllegalStateException("Particle access ABI duplicate");
        }
        node.interfaces.add(ACCESS);
        getter(node, "ice$previousX", "()D", "field_187123_c", "D", Opcodes.DRETURN);
        getter(node, "ice$previousY", "()D", "field_187124_d", "D", Opcodes.DRETURN);
        getter(node, "ice$previousZ", "()D", "field_187125_e", "D", Opcodes.DRETURN);
        getter(node, "ice$currentX", "()D", "field_187126_f", "D", Opcodes.DRETURN);
        getter(node, "ice$currentY", "()D", "field_187127_g", "D", Opcodes.DRETURN);
        getter(node, "ice$currentZ", "()D", "field_187128_h", "D", Opcodes.DRETURN);
        getter(node, "ice$textureIndexX", "()I", "field_94054_b", "I", Opcodes.IRETURN);
        getter(node, "ice$textureIndexY", "()I", "field_94055_c", "I", Opcodes.IRETURN);
        getter(node, "ice$particleScale", "()F", "field_70544_f", "F", Opcodes.FRETURN);
        getter(node, "ice$particleRed", "()F", "field_70552_h", "F", Opcodes.FRETURN);
        getter(node, "ice$particleGreen", "()F", "field_70553_i", "F", Opcodes.FRETURN);
        getter(node, "ice$particleBlue", "()F", "field_70551_j", "F", Opcodes.FRETURN);
        getter(node, "ice$particleAlpha", "()F", "field_82339_as", "F", Opcodes.FRETURN);
        getter(node, "ice$particleTexture", "()L" + TEXTURE + ";",
            "field_187119_C", "L" + TEXTURE + ";", Opcodes.ARETURN);
        getter(node, "ice$particleAngle", "()F", "field_190014_F", "F", Opcodes.FRETURN);
        getter(node, "ice$previousParticleAngle", "()F", "field_190015_G", "F",
            Opcodes.FRETURN);
    }

    private static void getter(ClassNode node, String methodName, String descriptor,
                               String fieldName, String fieldDescriptor,
                               int returnOpcode) {
        if (findMethod(node, methodName, descriptor) != null) {
            throw new IllegalStateException("duplicate Particle accessor " + methodName);
        }
        boolean field = false;
        for (org.objectweb.asm.tree.FieldNode candidate : node.fields) {
            if (fieldName.equals(candidate.name)
                && fieldDescriptor.equals(candidate.desc)) field = true;
        }
        if (!field) throw new IllegalStateException("missing Particle field " + fieldName);
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            methodName, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, PARTICLE,
            fieldName, fieldDescriptor));
        method.instructions.add(new InsnNode(returnOpcode));
        node.methods.add(method);
    }

    private static void requireClass(ClassNode node, String expected) {
        if (!expected.equals(node.name)) throw new IllegalStateException("target " + node.name);
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode method = findMethod(node, name, descriptor);
        if (method == null) throw new IllegalStateException("missing method " + name + descriptor);
        return method;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
