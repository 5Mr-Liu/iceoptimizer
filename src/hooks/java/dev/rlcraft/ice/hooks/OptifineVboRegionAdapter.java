package dev.rlcraft.ice.hooks;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Structural observer for OptiFine G5's already-batched VboRegion emitter.
 * The original multi-draw, buffer reset, and bounded compaction remain intact.
 */
final class OptifineVboRegionAdapter implements OptimizerBytecodeAdapter {
    static final String REGION = "net/optifine/render/VboRegion";
    static final String ACCESS =
        "dev/rlcraft/ice/optimizer/compat/optifine/OptifineVboRegionAccess";
    static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/OptifineRegionBootstrap";
    static final String FINISH = "finishDraw";
    static final String ORIGINAL = "ice$finishDrawOriginal";
    private static final String INDEX = "bufferIndexVertex";
    private static final String COUNT = "bufferCountVertex";
    private static final String LAYER = "layer";
    private static final String DRAW_MODE = "drawMode";
    private static final String BUFFER_ID = "glBufferId";
    private static final String POSITION_TOP = "positionTop";
    private static final String SIZE_USED = "sizeUsed";
    private static final String INT_BUFFER = "Ljava/nio/IntBuffer;";

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!REGION.equals(node.name)) {
            throw new IllegalStateException("OptiFine VboRegion target changed");
        }
        if (node.interfaces.contains(ACCESS) || findByName(node, ORIGINAL) != null) {
            throw new IllegalStateException("OptiFine VboRegion already adapted");
        }
        FieldNode layer = requireObjectField(node, LAYER);
        requireField(node, INDEX, INT_BUFFER);
        requireField(node, COUNT, INT_BUFFER);
        requireField(node, DRAW_MODE, "I");
        requireField(node, BUFFER_ID, "I");
        requireField(node, POSITION_TOP, "I");
        requireField(node, SIZE_USED, "I");

        MethodNode original = requireFinish(node);
        validateGraph(original);
        int access = original.access;
        String descriptor = original.desc;
        String signature = original.signature;
        List<String> exceptions = original.exceptions;
        original.name = ORIGINAL;
        original.access = (original.access
            & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_SYNCHRONIZED))
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;

        node.interfaces.add(ACCESS);
        node.methods.add(wrapper(access, descriptor, signature, exceptions));
        addObjectGetter(node, "ice$layer", LAYER, layer.desc);
        addIntBufferPositionGetter(node, "ice$indexPosition", INDEX);
        addIntBufferPositionGetter(node, "ice$countPosition", COUNT);
        addIntBufferCapacityGetter(node, "ice$commandCapacity", INDEX);
        addIntGetter(node, "ice$drawMode", DRAW_MODE);
        addIntGetter(node, "ice$bufferId", BUFFER_ID);
        addIntGetter(node, "ice$positionTop", POSITION_TOP);
        addIntGetter(node, "ice$sizeUsed", SIZE_USED);

        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode requireFinish(ClassNode node) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!FINISH.equals(method.name)) continue;
            Type result = Type.getReturnType(method.desc);
            Type[] arguments = Type.getArgumentTypes(method.desc);
            if (result.getSort() != Type.VOID || arguments.length != 1
                || arguments[0].getSort() != Type.OBJECT) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate OptiFine VboRegion finishDraw");
            found = method;
        }
        if (found == null || (found.access
            & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("OptiFine VboRegion finishDraw shape changed");
        }
        return found;
    }

    private static void validateGraph(MethodNode method) {
        int bind = 0;
        int setup = 0;
        int flips = 0;
        int multiDraw = 0;
        int limits = 0;
        int compacts = 0;
        int indexReads = 0;
        int countReads = 0;
        int drawModeReads = 0;
        int positionReads = 0;
        int sizeReads = 0;
        String argumentOwner = Type.getArgumentTypes(method.desc)[0].getInternalName();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (REGION.equals(call.owner) && "bindBuffer".equals(call.name)
                    && "()V".equals(call.desc)) bind++;
                if (argumentOwner.equals(call.owner) && "()V".equals(call.desc)) setup++;
                if ("java/nio/IntBuffer".equals(call.owner)
                    && "flip".equals(call.name)
                    && "()Ljava/nio/Buffer;".equals(call.desc)) flips++;
                if ("glMultiDrawArrays".equals(call.name)
                    && "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V".equals(call.desc)
                    && call.getOpcode() == Opcodes.INVOKESTATIC) multiDraw++;
                if ("java/nio/IntBuffer".equals(call.owner)
                    && "limit".equals(call.name)
                    && "(I)Ljava/nio/Buffer;".equals(call.desc)) limits++;
                if (REGION.equals(call.owner) && "compactRanges".equals(call.name)
                    && "(I)V".equals(call.desc)) compacts++;
            } else if (instruction instanceof FieldInsnNode
                && instruction.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (!REGION.equals(field.owner)) continue;
                if (INDEX.equals(field.name) && INT_BUFFER.equals(field.desc)) indexReads++;
                if (COUNT.equals(field.name) && INT_BUFFER.equals(field.desc)) countReads++;
                if (DRAW_MODE.equals(field.name) && "I".equals(field.desc)) drawModeReads++;
                if (POSITION_TOP.equals(field.name) && "I".equals(field.desc)) positionReads++;
                if (SIZE_USED.equals(field.name) && "I".equals(field.desc)) sizeReads++;
            }
        }
        if (bind != 1 || setup != 1 || flips != 2 || multiDraw != 1
            || limits != 2 || compacts != 1 || indexReads != 4
            || countReads != 4 || drawModeReads != 1 || positionReads != 1
            || sizeReads != 1) {
            throw new IllegalStateException("OptiFine VboRegion graph changed: "
                + bind + '/' + setup + '/' + flips + '/' + multiDraw + '/'
                + limits + '/' + compacts + '/' + indexReads + '/' + countReads
                + '/' + drawModeReads + '/' + positionReads + '/' + sizeReads);
        }
    }

    private static MethodNode wrapper(int access, String descriptor,
                                      String signature, List<String> exceptions) {
        MethodNode method = new MethodNode(Opcodes.ASM5, access, FINISH,
            descriptor, signature, exceptions == null ? null
                : exceptions.toArray(new String[exceptions.size()]));
        MethodVisitor code = method;
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "begin",
            "(Ljava/lang/Object;)J", false);
        code.visitVarInsn(Opcodes.LSTORE, 2);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitLabel(start);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(Opcodes.INVOKESPECIAL, REGION, ORIGINAL,
            descriptor, false);
        code.visitLabel(end);
        code.visitVarInsn(Opcodes.LLOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "end",
            "(JLjava/lang/Object;)V", false);
        code.visitInsn(Opcodes.RETURN);
        code.visitLabel(handler);
        code.visitVarInsn(Opcodes.ASTORE, 4);
        code.visitVarInsn(Opcodes.LLOAD, 2);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "abort",
            "(JLjava/lang/Object;Ljava/lang/Throwable;)V", false);
        code.visitVarInsn(Opcodes.ALOAD, 4);
        code.visitInsn(Opcodes.ATHROW);
        code.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        code.visitMaxs(0, 0);
        code.visitEnd();
        return method;
    }

    private static void addObjectGetter(ClassNode node, String name,
                                        String field, String fieldDescriptor) {
        MethodNode method = getter(node, name, "()Ljava/lang/Object;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, REGION, field, fieldDescriptor);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void addIntGetter(ClassNode node, String name, String field) {
        MethodNode method = getter(node, name, "()I");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, REGION, field, "I");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void addIntBufferPositionGetter(ClassNode node, String name,
                                                   String field) {
        MethodNode method = getter(node, name, "()I");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, REGION, field, INT_BUFFER);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer",
            "position", "()I", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void addIntBufferCapacityGetter(ClassNode node, String name,
                                                   String field) {
        MethodNode method = getter(node, name, "()I");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, REGION, field, INT_BUFFER);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer",
            "capacity", "()I", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static MethodNode getter(ClassNode node, String name, String descriptor) {
        if (find(node, name, descriptor) != null) {
            throw new IllegalStateException("duplicate VboRegion ABI " + name);
        }
        MethodNode method = new MethodNode(Opcodes.ASM5,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            name, descriptor, null, null);
        node.methods.add(method);
        return method;
    }

    private static FieldNode requireObjectField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && Type.getType(field.desc).getSort()
                == Type.OBJECT) return field;
        }
        throw new IllegalStateException("missing VboRegion object field " + name);
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) return;
        }
        throw new IllegalStateException("missing VboRegion field " + name + descriptor);
    }

    private static MethodNode findByName(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return method;
        return null;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
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
