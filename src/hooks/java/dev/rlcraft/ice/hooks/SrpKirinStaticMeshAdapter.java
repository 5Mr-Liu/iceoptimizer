package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Replaces reviewed SRP root renders with exact guarded static-branch batches. */
final class SrpKirinStaticMeshAdapter implements OptimizerBytecodeAdapter {
    static final String MODEL_RENDERER = "net/minecraft/client/model/ModelRenderer";
    static final String RENDER_METHOD = "func_78785_a";
    static final String RENDER_DESCRIPTOR = "(F)V";
    static final String MODEL_METHOD_DESCRIPTOR = "(Lnet/minecraft/entity/Entity;FFFFFF)V";
    static final String BRIDGE_OWNER = "dev/rlcraft/ice/optimizer/compat/srp/SrpKirinRenderBridge";
    static final String BRIDGE_DESCRIPTOR = "(Lnet/minecraft/client/model/ModelRenderer;F)Z";
    private static final Map<String, MethodReview[]> REVIEWS = reviews();

    @Override
    public byte[] transform(String transformedName, byte[] originalClass, TargetSpec target) {
        final MethodReview[] review = REVIEWS.get(target.className);
        if (review == null) throw new IllegalStateException("未审查的 SRP 模型目标：" + target.className);
        ClassReader reader = new ClassReader(originalClass);
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        final int[] matchedMethods = new int[review.length];
        final int[] renderCalls = new int[review.length];
        final int[] unexpectedRenderCalls = new int[1];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                final int methodIndex = find(review, name, descriptor);
                if (methodIndex >= 0) matchedMethods[methodIndex]++;
                return new MethodVisitor(Opcodes.ASM5, parent) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && MODEL_RENDERER.equals(owner)
                            && RENDER_METHOD.equals(methodName) && RENDER_DESCRIPTOR.equals(methodDescriptor)) {
                            if (methodIndex < 0) {
                                unexpectedRenderCalls[0]++;
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                                return;
                            }
                            renderCalls[methodIndex]++;
                            super.visitInsn(Opcodes.DUP2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "tryRender", BRIDGE_DESCRIPTOR, false);
                            Label originalRender = new Label();
                            Label done = new Label();
                            super.visitJumpInsn(Opcodes.IFEQ, originalRender);
                            super.visitInsn(Opcodes.POP2);
                            super.visitJumpInsn(Opcodes.GOTO, done);
                            super.visitLabel(originalRender);
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                            super.visitLabel(done);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, itf);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        StringBuilder drift = new StringBuilder();
        for (int i = 0; i < review.length; i++) {
            if (matchedMethods[i] != 1 || renderCalls[i] != review[i].renderCalls) {
                if (drift.length() > 0) drift.append(", ");
                drift.append(review[i].name).append('=').append(matchedMethods[i])
                    .append('/').append(renderCalls[i]).append('/').append(review[i].renderCalls);
            }
        }
        if (unexpectedRenderCalls[0] != 0 || drift.length() != 0) {
            throw new IllegalStateException("SRP 模型调用图变化：target=" + target.className
                + ", reviewed=" + drift + ", unexpectedRenderCalls=" + unexpectedRenderCalls[0]);
        }
        return writer.toByteArray();
    }

    private static int find(MethodReview[] reviews, String name, String descriptor) {
        for (int i = 0; i < reviews.length; i++) {
            if (reviews[i].name.equals(name) && reviews[i].descriptor.equals(descriptor)) return i;
        }
        return -1;
    }

    private static Map<String, MethodReview[]> reviews() {
        Map<String, MethodReview[]> values = new HashMap<String, MethodReview[]>();
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelEsor", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.inborn.ModelMudo", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.inborn.ModelNuuh", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.pure.preeminent.ModelJinjo", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.adapted.ModelBanoAdapted", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfVillager", 5);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfEnderman", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfHorse", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfHuman", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.crude.ModelCruxA", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelAlafha", 1);
        one(values, "com.dhanantry.scapeandrunparasites.client.model.entity.primitive.ModelNogla", 1);
        values.put("com.dhanantry.scapeandrunparasites.client.model.entity.derived.ModelKirin",
            new MethodReview[] {
                new MethodReview("func_78088_a", MODEL_METHOD_DESCRIPTOR, 1),
                new MethodReview("renderC", MODEL_METHOD_DESCRIPTOR, 1)
            });
        return Collections.unmodifiableMap(values);
    }

    private static void one(Map<String, MethodReview[]> values, String className, int calls) {
        values.put(className, new MethodReview[] {
            new MethodReview("func_78088_a", MODEL_METHOD_DESCRIPTOR, calls)
        });
    }

    private static final class MethodReview {
        private final String name;
        private final String descriptor;
        private final int renderCalls;

        private MethodReview(String name, String descriptor, int renderCalls) {
            this.name = name;
            this.descriptor = descriptor;
            this.renderCalls = renderCalls;
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
