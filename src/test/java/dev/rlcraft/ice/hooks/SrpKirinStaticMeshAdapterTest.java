package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class SrpKirinStaticMeshAdapterTest {
    private static final String SYNTHETIC_INTERNAL = "dev/rlcraft/ice/hooks/SyntheticSrpModel";
    private static final String KIRIN =
        "com.dhanantry.scapeandrunparasites.client.model.entity.derived.ModelKirin";
    private static final String VILLAGER =
        "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfVillager";
    private static final ReviewedModel[] MODELS = {
        model("com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelEsor",
            "fe7d549774d6059e1b07c92d3393248a3da15f7f124793e2f3f24e4096228b3c", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.inborn.ModelMudo",
            "5e3e3bed969bca051e5631b4e727426782ef1ab9f974cd04adc792674becc6bc", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.inborn.ModelNuuh",
            "c7ae8961609c6bc86c8383aaef913c7a62fe7defc39170d00df6748c7b8f9dc5", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.pure.preeminent.ModelJinjo",
            "31d27889acd6a2017daa9afed04487292399a5185bbf1be527cdecc1ecb0e78a", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.adapted.ModelBanoAdapted",
            "4e3338f0cdd51a953cfa3c9400a512b06df8b24bd6658f6abc9c4ab16963a6df", 1),
        model(VILLAGER, "5bf9604c527f8b320d6e5f1cf6f364adde6fad39173d9f1f20ccb535d19c4046", 5),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfEnderman",
            "d130e734742617dd7893bdbfe4fd630979b6b0929714bed966f9c3916b53ed2e", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfHorse",
            "cbe2650734cd9771b72e008faf884b86db10227e7ebb0a380d64f9feddb24a19", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfHuman",
            "78c79ab813b376adca231b9496c2a6f1a761b49640ad72f3f54f5bd49c8dba32", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.crude.ModelCruxA",
            "98ca0688627aedea91f0c8d156990a6e4ec2ab8f6d0309008567b29d0a750602", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelAlafha",
            "69e69955f9418516c2022825775bc2fce47574eac3cf9ab24cf1e85f69d07e2c", 1),
        model("com.dhanantry.scapeandrunparasites.client.model.entity.primitive.ModelNogla",
            "9d881ff4dd75e8368608b3e0bc185194ee8b8bf004195e99e1aadbabb33ca262", 1),
        model(KIRIN, "f9a63c342e850b82e6ea7e57b64986db72aafb8197b649175425dd93518bd8e4", 2)
    };

    @Test
    public void injectsReviewedKirinAndFiveRootVillagerGraphs() {
        byte[] kirin = syntheticClass(1, 1);
        byte[] transformedKirin = new SrpKirinStaticMeshAdapter().transform(KIRIN, kirin, targetFor(KIRIN));
        assertEquals(2, bridgeCalls(transformedKirin));
        assertEquals(2, fallbackCalls(transformedKirin));

        byte[] villager = syntheticClass(5, -1);
        byte[] transformedVillager =
            new SrpKirinStaticMeshAdapter().transform(VILLAGER, villager, targetFor(VILLAGER));
        assertEquals(5, bridgeCalls(transformedVillager));
        assertEquals(5, fallbackCalls(transformedVillager));
        new ClassReader(transformedVillager);
    }

    @Test
    public void rejectsAnyReviewedRenderCallGraphDrift() {
        try {
            new SrpKirinStaticMeshAdapter().transform(KIRIN, syntheticClass(2, 0), targetFor(KIRIN));
            fail("adapter must reject an SRP render-call graph drift");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("调用图变化"));
            assertTrue(expected.getMessage().contains("func_78088_a=1/2/1"));
            assertTrue(expected.getMessage().contains("renderC=1/0/1"));
        }
    }

    @Test
    public void transformsAllThirteenConfiguredRealSrpModelsWhenAvailable() throws Exception {
        String configured = System.getProperty("ice.srp.jar", "").trim();
        Assume.assumeTrue("run with -PsrpJar=<jar> for the real-JAR integration test", !configured.isEmpty());
        File jarFile = new File(configured);
        Assume.assumeTrue("configured SRP JAR must exist", jarFile.isFile());

        JarFile jar = new JarFile(jarFile);
        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            for (ReviewedModel model : MODELS) {
                JarEntry entry = jar.getJarEntry(model.entry);
                assertTrue("reviewed SRP model must exist: " + model.className, entry != null);
                byte[] original = readFully(jar.getInputStream(entry));
                assertEquals(model.className, model.sha256, CoreClassFingerprint.sha256(original));
                byte[] transformed = new IceClientOptimizerTransformer().transform(
                    model.className, model.className, original);
                assertFalse("the exact reviewed class hash must install the adapter: " + model.className,
                    Arrays.equals(original, transformed));
                assertEquals(model.className, model.renderCalls, bridgeCalls(transformed));
                assertEquals(model.className, model.renderCalls, fallbackCalls(transformed));
                new ClassReader(transformed);
                ByteLoader loader = new ByteLoader(dependencies);
                assertEquals(model.className, loader.define(model.className, transformed).getName());
            }
        } finally {
            dependencies.close();
            jar.close();
        }
    }

    private static TargetSpec targetFor(String className) {
        return new TargetSpec(className, "srp-static-mesh", "srp-model-static-branches",
            Collections.<String>emptySet());
    }

    private static byte[] syntheticClass(int renderCalls, int renderCCalls) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTHETIC_INTERNAL, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "mainbody", "L" + SrpKirinStaticMeshAdapter.MODEL_RENDERER + ";",
            null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        addRenderMethod(writer, "func_78088_a", renderCalls);
        if (renderCCalls >= 0) addRenderMethod(writer, "renderC", renderCCalls);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addRenderMethod(ClassWriter writer, String methodName, int calls) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName,
            SrpKirinStaticMeshAdapter.MODEL_METHOD_DESCRIPTOR, null, null);
        method.visitCode();
        for (int i = 0; i < calls; i++) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, SYNTHETIC_INTERNAL, "mainbody",
                "L" + SrpKirinStaticMeshAdapter.MODEL_RENDERER + ";");
            method.visitVarInsn(Opcodes.FLOAD, 7);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SrpKirinStaticMeshAdapter.MODEL_RENDERER,
                SrpKirinStaticMeshAdapter.RENDER_METHOD, SrpKirinStaticMeshAdapter.RENDER_DESCRIPTOR, false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 8);
        method.visitEnd();
    }

    private static int bridgeCalls(byte[] bytes) {
        return countCalls(bytes, Opcodes.INVOKESTATIC, SrpKirinStaticMeshAdapter.BRIDGE_OWNER,
            "tryRender", SrpKirinStaticMeshAdapter.BRIDGE_DESCRIPTOR);
    }

    private static int fallbackCalls(byte[] bytes) {
        return countCalls(bytes, Opcodes.INVOKEVIRTUAL, SrpKirinStaticMeshAdapter.MODEL_RENDERER,
            SrpKirinStaticMeshAdapter.RENDER_METHOD, SrpKirinStaticMeshAdapter.RENDER_DESCRIPTOR);
    }

    private static int countCalls(byte[] bytes, final int expectedOpcode, final String expectedOwner,
                                  final String expectedName, final String expectedDescriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean itf) {
                        if (opcode == expectedOpcode && expectedOwner.equals(owner)
                            && expectedName.equals(name) && expectedDescriptor.equals(descriptor)) count[0]++;
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static ReviewedModel model(String className, String sha256, int calls) {
        return new ReviewedModel(className, sha256, calls);
    }

    private static byte[] readFully(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static final class ReviewedModel {
        private final String className;
        private final String entry;
        private final String sha256;
        private final int renderCalls;

        private ReviewedModel(String className, String sha256, int renderCalls) {
            this.className = className;
            this.entry = className.replace('.', '/') + ".class";
            this.sha256 = sha256;
            this.renderCalls = renderCalls;
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
