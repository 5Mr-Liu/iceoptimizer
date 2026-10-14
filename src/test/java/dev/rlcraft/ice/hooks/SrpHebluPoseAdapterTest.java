package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Executes the real SRP pose bytecode with headless entity/part field fixtures. */
public class SrpHebluPoseAdapterTest {
    private static final String MODEL = SrpHebluPoseAdapter.TARGET;
    private static final String BASE = "com/dhanantry/scapeandrunparasites/client/model/ModelSRP";
    private static final String COSMICAL = "com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityPCosmical";
    private static final String ENTITY = "com/dhanantry/scapeandrunparasites/entity/monster/derived/EntityHeblu";
    private static final String TEST = "dev/rlcraft/ice/hooks/SrpHebluPoseAdapterTest";
    private static final String[] PART_FLOATS = { "field_78795_f", "field_78796_g", "field_78808_h",
        "field_82906_o", "field_82908_p", "field_82907_q", "field_78800_c", "field_78797_d", "field_78798_e" };
    private static int cosCalls;

    @Test
    public void realAdapterCreatesLocalCosinesAndPreservesOtherMethods() throws Exception {
        byte[] source = read(MODEL);
        byte[] transformed = transform(source);
        ClassNode before = parse(source);
        ClassNode after = parse(transformed);
        assertEquals(before.methods.size() + 3, after.methods.size());
        assertEquals(6, calls(method(after, SrpHebluPoseAdapter.METHOD), "java/lang/Math", "cos"));
        assertEquals(32, calls(method(after, SrpHebluPoseAdapter.ORIGINAL), MODEL, "swingX"));
        assertEquals(4, calls(method(after, SrpHebluPoseAdapter.ORIGINAL), MODEL, "swingZ"));
        assertEquals(32, calls(method(after, SrpHebluPoseAdapter.METHOD), MODEL, "ice$hebLuSwingX"));
        assertEquals(4, calls(method(after, SrpHebluPoseAdapter.METHOD), MODEL, "ice$hebLuSwingZ"));
        assertEquals(1, calls(method(after, SrpHebluPoseAdapter.METHOD), SrpHebluPoseAdapter.BRIDGE, "usePoseReuse"));
        assertEquals(method(before, "setRotationAnglesCosmical").instructions.size(),
            method(after, "setRotationAnglesCosmical").instructions.size());
    }

    @Test
    public void realPosesMatchBitForBitAcrossMotionLayersEntitiesAndRuntimePause() throws Exception {
        Fixture original = new Fixture(read(MODEL));
        Fixture optimized = new Fixture(transform(read(MODEL)));
        OptimizerRegistry.breaker(OptimizationModule.SRP_POSE_CACHE).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.SRP_POSE_CACHE).patchInstalled(MODEL, "pose-test");
        try {
            for (int frame = 0; frame < 192; frame++) {
                boolean flying = frame % 3 == 0;
                boolean still = frame % 4 == 0;
                boolean paused = frame % 11 == 0;
                OptimizerRegistry.setRuntimePaused(paused);
                original.state(frame, flying, still);
                optimized.state(frame, flying, still);
                float partial = new float[] { 0.0F, 0.25F, 0.5F, 0.99F }[frame % 4];
                float limb = frame == 0 ? -0.0F : (frame - 96) * 0.733F + partial;
                float amount = (frame % 7) * 0.173F;
                float age = frame * 1.25F + partial;
                int beforeCalls = original.pose(limb, amount, age);
                int afterCalls = optimized.pose(limb, amount, age);
                assertEquals(flying ? 4 : still ? 0 : 16, beforeCalls);
                assertEquals(paused ? beforeCalls : flying || !still ? 2 : 0, afterCalls);
                original.assertSameParts(optimized, "normal frame " + frame);
                original.cosmical(limb, amount, age);
                optimized.cosmical(limb, amount, age);
                original.assertSameParts(optimized, "cosmical frame " + frame);
            }
            OptimizerRegistry.setRuntimePaused(false);
            OptimizerRegistry.breaker(OptimizationModule.SRP_POSE_CACHE).configure(false, 3);
            optimized.state(193, false, false);
            assertEquals(16, optimized.pose(1.125F, 0.75F, 34.5F));
        } finally {
            OptimizerRegistry.setRuntimePaused(false);
            OptimizerRegistry.breaker(OptimizationModule.SRP_POSE_CACHE).configure(false, 3);
        }
    }

    @Test
    public void rejectsRepeatedTransformationAndChangedSwingGraph() throws Exception {
        byte[] source = read(MODEL);
        try {
            transform(transform(source));
            fail("duplicate transformation must be rejected at class loading");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already adapted"));
        }
        ClassNode changed = parse(source);
        for (AbstractInsnNode instruction : method(changed, SrpHebluPoseAdapter.METHOD).instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && "swingX".equals(((MethodInsnNode) instruction).name)) {
                ((MethodInsnNode) instruction).name = "swingY";
                break;
            }
        }
        try {
            transform(write(changed));
            fail("changed expressions must not be rewritten");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("graph changed"));
        }
    }

    public static double countedCos(double argument) {
        cosCalls++;
        return Math.cos(argument);
    }

    /** MathHelper's original float lookup, shared by both headless copies. */
    public static float tableCos(float value) {
        return net.minecraft.util.math.MathHelper.cos(value);
    }

    private static byte[] transform(byte[] bytes) {
        return new SrpHebluPoseAdapter().transform(MODEL.replace('/', '.'), bytes,
            new TargetSpec(MODEL.replace('/', '.'), "srp-pose-cache", "srp-heblu-pose-local", Collections.<String>emptySet()));
    }

    private static byte[] read(String name) throws Exception {
        File file = new File(System.getProperty("ice.dregora.srp.jar", System.getProperty("ice.srp.jar", "")));
        Assume.assumeTrue("run with -PsrpJar=<jar>", file.isFile());
        try (JarFile jar = new JarFile(file); InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) >= 0) output.write(buffer, 0, size);
            return output.toByteArray();
        }
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return method;
        throw new AssertionError("Missing " + name);
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }

    private static byte[] headlessModel(byte[] bytes) {
        ClassNode node = parse(bytes);
        MethodNode constructor = method(node, "<init>");
        constructor.instructions.clear();
        constructor.tryCatchBlocks.clear();
        constructor.localVariables = null;
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("java/lang/Math".equals(call.owner) && "cos".equals(call.name)) {
                        call.owner = TEST;
                        call.name = "countedCos";
                    }
                }
            }
        }
        return write(node);
    }

    private static ClassWriter stub(String name, String parent) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, null);
        org.objectweb.asm.MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        return writer;
    }

    private static void getter(ClassWriter writer, String owner, String name, String descriptor) {
        writer.visitField(Opcodes.ACC_PUBLIC, name, descriptor, null, null).visitEnd();
        org.objectweb.asm.MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()" + descriptor, null, null);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, name, descriptor);
        method.visitInsn("F".equals(descriptor) ? Opcodes.FRETURN : Opcodes.IRETURN);
        method.visitMaxs(0, 0);
    }

    private static final class Fixture {
        final Object model;
        final Object entity;
        final Object otherEntity;
        final Class<?> entityType;
        final Class<?> partType;
        final Field[] modelFields;
        final Method pose;
        final Method cosmical;
        Object currentEntity;

        Fixture(byte[] modelBytes) throws Exception {
            Map<String, byte[]> definitions = new HashMap<String, byte[]>();
            definitions.put("net/minecraft/entity/Entity", stub("net/minecraft/entity/Entity", "java/lang/Object").toByteArray());
            definitions.put("com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityParasiteBase",
                stub("com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityParasiteBase", "net/minecraft/entity/Entity").toByteArray());
            definitions.put("net/minecraft/client/model/ModelBase", stub("net/minecraft/client/model/ModelBase", "java/lang/Object").toByteArray());
            ClassWriter part = stub(SrpHebluPoseAdapter.PART, "java/lang/Object");
            for (String field : PART_FLOATS) part.visitField(Opcodes.ACC_PUBLIC, field, "F", null, null).visitEnd();
            part.visitField(Opcodes.ACC_PUBLIC, "field_78807_k", "Z", null, null).visitEnd();
            definitions.put(SrpHebluPoseAdapter.PART, part.toByteArray());
            ClassWriter cosmicalEntity = stub(COSMICAL, "com/dhanantry/scapeandrunparasites/entity/ai/misc/EntityParasiteBase");
            getter(cosmicalEntity, COSMICAL, "showC", "I");
            getter(cosmicalEntity, COSMICAL, "shakingC", "I");
            definitions.put(COSMICAL, cosmicalEntity.toByteArray());
            ClassWriter heblu = stub(ENTITY, COSMICAL);
            for (String field : new String[] { "getLeft", "getRight", "getHead", "getaaa" }) getter(heblu, ENTITY, field, "F");
            getter(heblu, ENTITY, "getFlyingState", "Z");
            getter(heblu, ENTITY, "getStillAni", "Z");
            getter(heblu, ENTITY, "getParasiteStatus", "B");
            definitions.put(ENTITY, heblu.toByteArray());
            ClassWriter math = stub("net/minecraft/util/math/MathHelper", "java/lang/Object");
            org.objectweb.asm.MethodVisitor cos = math.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "func_76134_b", "(F)F", null, null);
            cos.visitVarInsn(Opcodes.FLOAD, 0);
            cos.visitMethodInsn(Opcodes.INVOKESTATIC, TEST, "tableCos", "(F)F", false);
            cos.visitInsn(Opcodes.FRETURN);
            cos.visitMaxs(0, 0);
            definitions.put("net/minecraft/util/math/MathHelper", math.toByteArray());
            definitions.put(BASE, headlessModel(read(BASE)));
            definitions.put(MODEL, headlessModel(modelBytes));
            ByteLoader loader = new ByteLoader(definitions);
            Class<?> modelType = loader.loadClass(MODEL.replace('/', '.'));
            entityType = loader.loadClass(ENTITY.replace('/', '.'));
            partType = loader.loadClass(SrpHebluPoseAdapter.PART.replace('/', '.'));
            model = modelType.newInstance();
            entity = entityType.newInstance();
            otherEntity = entityType.newInstance();
            modelFields = modelType.getFields();
            int count = 0;
            for (Field field : modelFields) {
                if (field.getType() != partType) continue;
                Object value = partType.newInstance();
                for (int index = 0; index < PART_FLOATS.length; index++) {
                    partType.getField(PART_FLOATS[index]).setFloat(value, (index - 4) * 0.037F);
                }
                field.set(model, value);
                count++;
            }
            assertTrue("exercise the complete real Heblu model", count > 300);
            Class<?> baseEntity = loader.loadClass("net.minecraft.entity.Entity");
            Class<?>[] parameters = { Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, baseEntity };
            pose = modelType.getMethod(SrpHebluPoseAdapter.METHOD, parameters);
            cosmical = modelType.getMethod("setRotationAnglesCosmical", parameters);
        }

        void state(int frame, boolean flying, boolean still) throws Exception {
            currentEntity = frame % 2 == 0 ? entity : otherEntity;
            entityType.getField("getFlyingState").setBoolean(currentEntity, flying);
            entityType.getField("getStillAni").setBoolean(currentEntity, still);
            entityType.getField("getParasiteStatus").setByte(currentEntity, (byte) (frame % 3));
            entityType.getField("getaaa").setFloat(currentEntity, frame * 0.917F);
            entityType.getField("getLeft").setFloat(currentEntity, frame % 5 == 0 ? 0F : 1F);
            entityType.getField("getRight").setFloat(currentEntity, frame % 7 == 0 ? 0F : 1F);
            entityType.getField("getHead").setFloat(currentEntity, frame % 11 == 0 ? 0F : 1F);
            entityType.getField("shakingC").setInt(currentEntity, frame % 2);
            entityType.getField("showC").setInt(currentEntity, frame % 3 - 1);
        }

        int pose(float limb, float amount, float age) throws Exception {
            cosCalls = 0;
            pose.invoke(model, limb, amount, age, 0.23F, -0.19F, 0.0625F, currentEntity);
            return cosCalls;
        }

        void cosmical(float limb, float amount, float age) throws Exception {
            cosmical.invoke(model, limb, amount, age, 0.23F, -0.19F, 0.0625F, currentEntity);
        }

        void assertSameParts(Fixture other, String state) throws Exception {
            for (Field field : modelFields) {
                if (field.getType() != partType) continue;
                Object left = field.get(model);
                Object right = other.model.getClass().getField(field.getName()).get(other.model);
                for (String transform : PART_FLOATS) {
                    float before = partType.getField(transform).getFloat(left);
                    float after = other.partType.getField(transform).getFloat(right);
                    assertEquals(state + " " + field.getName() + "." + transform,
                        Float.floatToRawIntBits(before), Float.floatToRawIntBits(after));
                }
                assertEquals(state + " visibility " + field.getName(),
                    partType.getField("field_78807_k").getBoolean(left),
                    other.partType.getField("field_78807_k").getBoolean(right));
            }
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        ByteLoader(Map<String, byte[]> definitions) {
            super(SrpHebluPoseAdapterTest.class.getClassLoader());
            this.definitions = definitions;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            Class<?> found = findLoadedClass(name);
            if (found == null) found = defineClass(name, bytes, 0, bytes.length);
            if (resolve) resolveClass(found);
            return found;
        }
    }
}
