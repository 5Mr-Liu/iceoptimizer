package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Real-JAR regression coverage for the 0.8.0 measured hitch adapters. */
public class MeasuredHotspotAdapterTest {
    private static final Sample[] BETTER_CAVES = {
        sample("com.yungnickyoung.minecraft.bettercaves.noise.NoiseTuple",
            "7bb278fc81e6bd182f7b606f75e2447f3bb29d676e70ff6038f6d58aade124b5"),
        sample("com.yungnickyoung.minecraft.bettercaves.noise.NoiseColumn",
            "dc27955758621f0a38693ada4db81a993e644b73f3eb4485280132cb952b3872"),
        sample("com.yungnickyoung.minecraft.bettercaves.noise.NoiseGen",
            "575b73c9fa282ab335b04a6df650305a2deb63535c4cb0ed00bed2a10ed77f64"),
        sample("com.yungnickyoung.minecraft.bettercaves.world.carver.cave.CaveCarver",
            "aa8c11b25eaaf0848debd37432d520ab387165b1cb3979ca72414752288f5c7d")
    };
    private static final Sample[] BETTER_FOLIAGE = {
        sample("mods.betterfoliage.client.integration.OptifineCustomColors", null)
    };
    private static final Sample[] QUALITY_TOOLS = {
        sample("com.tmtravlr.qualitytools.CommonEventHandler",
            "4d383df7c4e5bb1464b6036201a2bd2ee84a2137235076d515f019f5a5c0b655")
    };
    private static final Sample[] QUARK = {
        sample("vazkii.quark.client.feature.ItemsFlashBeforeExpiring",
            "6268c06db29b380fc320c3277dc05e734ee74a0c5d3daa3454b2bfc228669a77")
    };

    @Test
    public void transformsAndDefinesAllConfiguredMeasuredHotspots() throws Exception {
        boolean configured = false;
        configured |= verify("ice.bettercaves.jar", BETTER_CAVES);
        configured |= verify("ice.betterfoliage.jar", BETTER_FOLIAGE);
        configured |= verify("ice.qualitytools.jar", QUALITY_TOOLS);
        configured |= verify("ice.quark.jar", QUARK);
        Assume.assumeTrue("configure one or more measured hotspot JARs", configured);
    }

    @Test
    public void anUnknownClassHashStillTransformsWhenTheRequiredStructureMatches() throws Exception {
        String configured = System.getProperty("ice.bettercaves.jar", "").trim();
        Assume.assumeTrue("run with -PbetterCavesJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            String className = BETTER_CAVES[0].className;
            byte[] altered = withUnrelatedField(read(jar, className));
            TargetSpec target = OptimizerTargetCatalog.find(className);
            assertNotNull(target);
            assertFalse(target.accepts(CoreClassFingerprint.sha256(altered)));
            byte[] transformed = new IceOptimizerTransformer().transform(
                className, className, altered);
            assertFalse(Arrays.equals(altered, transformed));
            assertTrue(hasField(transformed, BetterCavesNoiseTupleAdapter.VALUES_FIELD, "[D"));
        } finally {
            jar.close();
        }
    }

    private boolean verify(String property, Sample[] samples) throws Exception {
        String configured = System.getProperty(property, "").trim();
        if (configured.isEmpty()) return false;
        File file = new File(configured);
        assertTrue(file.isFile());
        JarFile jar = new JarFile(file);
        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { file.toURI().toURL() }, getClass().getClassLoader());
        try {
            Map<Sample, byte[]> transformedClasses = new LinkedHashMap<Sample, byte[]>();
            for (Sample sample : samples) {
                byte[] original = read(jar, sample.className);
                String fingerprint = CoreClassFingerprint.sha256(original);
                if (sample.sha256 != null) assertEquals(sample.sha256, fingerprint);
                TargetSpec target = OptimizerTargetCatalog.find(sample.className);
                assertNotNull("missing target " + sample.className, target);
                OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(target.adapterId);
                assertNotNull("missing adapter " + target.adapterId, adapter);
                byte[] transformed = adapter.transform(sample.className, original, target);
                assertFalse(sample.className, Arrays.equals(original, transformed));
                new ClassReader(transformed);
                verifyInstalledCalls(sample.className, transformed);
                transformedClasses.put(sample, transformed);
            }
            ByteLoader loader = new ByteLoader(dependencies);
            for (Map.Entry<Sample, byte[]> entry : transformedClasses.entrySet()) {
                Class<?> type = loader.define(entry.getKey().className, entry.getValue());
                assertEquals(entry.getKey().className, type.getName());
                if (!entry.getKey().className.endsWith(".OptifineCustomColors")) {
                    type.getDeclaredConstructors();
                    type.getDeclaredMethods();
                }
            }
            return true;
        } finally {
            dependencies.close();
            jar.close();
        }
    }

    private static void verifyInstalledCalls(String className, byte[] transformed) {
        if (className.endsWith(".NoiseTuple")) {
            assertTrue(hasInterface(transformed, BetterCavesNoiseTupleAdapter.ACCESS));
            assertTrue(hasField(transformed, BetterCavesNoiseTupleAdapter.VALUES_FIELD, "[D"));
            assertTrue(hasMethod(transformed, BetterCavesNoiseTupleAdapter.COPY_METHOD));
            assertTrue(hasMethod(transformed, BetterCavesNoiseTupleAdapter.BLEND_METHOD));
        } else if (className.endsWith(".NoiseColumn")) {
            assertTrue(hasField(transformed, BetterCavesNoiseColumnAdapter.VALUES_FIELD,
                "[Lcom/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple;"));
            assertTrue(hasMethod(transformed, BetterCavesNoiseColumnAdapter.COPY_METHOD));
        } else if (className.endsWith(".NoiseGen")) {
            assertTrue(hasMethod(transformed, BetterCavesNoiseGenAdapter.RAW_METHOD));
            assertEquals(5, countCalls(transformed, BetterCavesNoiseGenAdapter.BRIDGE,
                "isPipelineEnabled", "()Z"));
            assertEquals(4, countCalls(transformed,
                "com/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple",
                BetterCavesNoiseTupleAdapter.BLEND_METHOD,
                "(Lcom/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple;F"
                    + "Lcom/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple;F)"
                    + "Lcom/yungnickyoung/minecraft/bettercaves/noise/NoiseTuple;"));
        } else if (className.endsWith(".CaveCarver")) {
            assertEquals(1, countCalls(transformed, BetterCavesCaveCarverAdapter.BRIDGE,
                "isEnabled", "()Z"));
        } else if (className.endsWith(".OptifineCustomColors")) {
            assertEquals(1, countCalls(transformed, BetterFoliageOptifineColorAdapter.BRIDGE,
                "isCustomColorsEnabled", "(Ljava/lang/Object;)Z"));
            assertEquals(0, countCalls(transformed, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;"));
        } else if (className.endsWith(".CommonEventHandler")) {
            assertEquals(1, countCalls(transformed, QualityToolsAttributeAdapter.BRIDGE,
                "shouldRefresh", "(Lnet/minecraft/entity/EntityLivingBase;)Z"));
        } else if (className.endsWith(".ItemsFlashBeforeExpiring")) {
            assertEquals(1, countCalls(transformed, QuarkItemSyncAdapter.BRIDGE,
                "decision", "(Lnet/minecraft/entity/item/EntityItem;II)I"));
        }
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String methodDescriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean hasInterface(byte[] bytes, final String name) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String className,
                                        String signature, String superName, String[] interfaces) {
                if (interfaces != null) {
                    for (String candidate : interfaces) if (name.equals(candidate)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasField(byte[] bytes, final String name, final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public FieldVisitor visitField(int access, String candidate, String desc,
                                                     String signature, Object value) {
                if (name.equals(candidate) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasMethod(byte[] bytes, final String name) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String candidate,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                if (name.equals(candidate)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static byte[] withUnrelatedField(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public void visitEnd() {
                FieldVisitor field = super.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                    "ice$unknownHashProbe", "I", null, null);
                if (field != null) field.visitEnd();
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static Sample sample(String className, String sha256) {
        return new Sample(className, sha256);
    }

    private static final class Sample {
        private final String className;
        private final String sha256;
        private Sample(String className, String sha256) {
            this.className = className;
            this.sha256 = sha256;
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
