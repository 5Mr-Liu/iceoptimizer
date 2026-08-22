package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Real RLCraft JAR regression coverage for the 0.6.0 combat/entity adapters. */
public class CombatOptimizationAdapterTest {
    private static final Map<String, String> LYCANITES = targets(new String[][] {
        { "com.lycanitesmobs.client.obj.TessellatorModel",
            "867521f1052053ae7c44d82d46b2d45b0aad37e30bd4a74bc80ae405bf7a391e" },
        { "com.lycanitesmobs.client.obj.VBOModel",
            "8794ab3d2711def5cbb3fc83a15fd737e0332b667ac7369715a909ba516ed26e" },
        { "com.lycanitesmobs.client.model.Animator",
            "e0302ea4e18af91408607cfded6af7a2e8fa91cdcfc9bcd4fa3844425d166a4f" },
        { "com.lycanitesmobs.client.model.ModelObjPart",
            "c9ffb5b9df4fd2b1e965884848cda9d116d8e13a24cee3ae78ef428278f39c3d" },
        { "com.lycanitesmobs.client.model.ModelObjAnimationFrame",
            "6164a00b2a018210c051a46d2d2accc5cd22744f92d252f45c4fb55312c00d4a" },
        { "com.lycanitesmobs.client.model.ModelCreatureObj",
            "bfa7479afa24a1a5aaefc8b38663ce4aa54245409b444e69dc64f1ce43d1285f" },
        { "com.lycanitesmobs.client.model.ModelItemBase",
            "820b826d4541cc07b462d999ce241f298201df1bc6d337e0e03814ce4444422e" },
        { "com.lycanitesmobs.client.model.ModelObjOld",
            "fcbd5d8dd469be29d1ddf410039ba0406267e50238c1173130d2fed4c29abc17" },
        { "com.lycanitesmobs.PotionEffects",
            "b655b709968265f928f061cae44530e95b8756f9877d0891f7729017c6f47497" }
    });

    private static final Map<String, String> MOBENDS = targets(new String[][] {
        { "goblinbob.mobends.core.client.model.ModelPart",
            "886ce5aae146de8842d8d153bbca3af8646f6b2aa3e9120d1b2b8c9db0a0cf5d" },
        { "goblinbob.mobends.core.math.Quaternion",
            "eeb38ef0d70bbf041d0faa40337499978c0c95b7639f1dc5356e4ad905fdc43c" },
        { "goblinbob.mobends.core.util.GlHelper",
            "bf1669ae89d2ff11a030b7e6895c348f5039190623b7aa3c0a14b09d13c59ca2" },
        { "goblinbob.mobends.core.data.LivingEntityData",
            "4d03784125e9bc2af3cf6cf6e126513f1aeb744012f6b0af632422c382a4c1e5" }
    });

    private static final Map<String, String> ICE_AND_FIRE = targets(new String[][] {
        { "com.github.alexthe666.iceandfire.client.model.animator.IceAndFireTabulaModelAnimator",
            "f8eb1fbe6ecb443e3e20819862cc5d020eafc96d2c531975a642ce2694b17016" },
        { "com.github.alexthe666.iceandfire.entity.EntitySeaSerpent",
            "6f391210e5e82f92f53fed93b176e27139a353b38201777519d782510427e740" }
    });

    @Test
    public void transformsAndDefinesAllReviewedLycanitesCombatClasses() throws Exception {
        File jarFile = configuredJar("ice.lycanites.jar", "run with -PlycanitesJar=<jar>");
        JarFile jar = new JarFile(jarFile);
        ByteLoader loader = new ByteLoader(new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            Map<String, byte[]> transformed = transformAll(jar, LYCANITES);
            assertEquals(1, countCalls(transformed.get("com.lycanitesmobs.client.obj.TessellatorModel"),
                LycanitesObjRenderAdapter.BRIDGE, "tryRender"));
            assertEquals(1, countCalls(transformed.get("com.lycanitesmobs.client.obj.VBOModel"),
                LycanitesObjRenderAdapter.BRIDGE, "tryRender"));
            assertEquals(35, countCalls(transformed.get("com.lycanitesmobs.PotionEffects"),
                LycanitesPotionEffectsAdapter.TARGET, LycanitesPotionEffectsAdapter.HELPER));

            for (String className : LYCANITES.keySet()) {
                assertEquals(className, loader.define(className, transformed.get(className)).getName());
            }
        } finally {
            loader.close();
            jar.close();
        }
    }

    @Test
    public void transformsDefinesAndExecutesReviewedMoBendsClasses() throws Exception {
        File jarFile = configuredJar("ice.mobends.jar", "run with -PmoBendsJar=<jar>");
        JarFile jar = new JarFile(jarFile);
        ByteLoader loader = new ByteLoader(new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            Map<String, byte[]> transformed = transformAll(jar, MOBENDS);
            Class<?> quaternion = loader.define("goblinbob.mobends.core.math.Quaternion",
                transformed.get("goblinbob.mobends.core.math.Quaternion"));
            assertTrue(Arrays.asList(quaternion.getInterfaces()).contains(
                dev.rlcraft.ice.optimizer.compat.mobends.MoBendsQuaternionAccess.class));
            verifyQuaternionCache(quaternion);

            Class<?> modelPart = loader.define("goblinbob.mobends.core.client.model.ModelPart",
                transformed.get("goblinbob.mobends.core.client.model.ModelPart"));
            assertNotNull(modelPart.getMethod("applyCharacterTransform", Float.TYPE));
            assertNotNull(modelPart.getMethod("applyCharacterTransform", Float.TYPE,
                loader.loadClass("goblinbob.mobends.core.math.matrix.IMat4x4d")));
            byte[] modelPartBytes = transformed.get("goblinbob.mobends.core.client.model.ModelPart");
            assertEquals(0, countCallsInMethod(modelPartBytes, "renderPart", "(F)V",
                "java/util/List", "iterator"));
            assertEquals(0, countCallsInMethod(modelPartBytes, "renderJustPart", "(F)V",
                "java/util/List", "iterator"));
            assertEquals(2, countCallsInMethod(modelPartBytes, "renderPart", "(F)V",
                "java/util/List", null));
            assertEquals(2, countCallsInMethod(modelPartBytes, "renderJustPart", "(F)V",
                "java/util/List", null));

            assertEquals("goblinbob.mobends.core.data.LivingEntityData",
                loader.define("goblinbob.mobends.core.data.LivingEntityData",
                    transformed.get("goblinbob.mobends.core.data.LivingEntityData")).getName());
            assertEquals("goblinbob.mobends.core.util.GlHelper",
                loader.define("goblinbob.mobends.core.util.GlHelper",
                    transformed.get("goblinbob.mobends.core.util.GlHelper")).getName());
        } finally {
            loader.close();
            jar.close();
        }
    }

    @Test
    public void transformsAndDefinesReviewedIceAndFireClasses() throws Exception {
        File jarFile = configuredJar("ice.iceandfire.jar", "run with -PiceAndFireJar=<jar>");
        File llibrary = optionalConfiguredJar("ice.llibrary.jar");
        if (llibrary == null) llibrary = siblingJar(jarFile, "llibrary");
        Assume.assumeTrue("Ice and Fire verifier requires LLibrary", llibrary != null && llibrary.isFile());
        JarFile jar = new JarFile(jarFile);
        ByteLoader loader = new ByteLoader(new URL[] {
            jarFile.toURI().toURL(), llibrary.toURI().toURL()
        }, getClass().getClassLoader());
        try {
            Map<String, byte[]> transformed = transformAll(jar, ICE_AND_FIRE);
            byte[] animator = transformed.get(
                "com.github.alexthe666.iceandfire.client.model.animator.IceAndFireTabulaModelAnimator");
            assertEquals(1, countCalls(animator, IceAndFirePoseAdapter.BRIDGE, "usePoseLookup"));
            byte[] serpent = transformed.get("com.github.alexthe666.iceandfire.entity.EntitySeaSerpent");
            int emptyArrays = countCalls(serpent, IceAndFireSeaSerpentAdapter.BRIDGE,
                "emptyParticleArgs");
            int zeroArrays = countCalls(serpent, IceAndFireSeaSerpentAdapter.BRIDGE,
                "zeroParticleArgs");
            // Ice and Fire 1.7.1 used {0} in the slam path; Dregora 2.0.9
            // changed that site to an empty array.  Both retain exactly the two
            // reviewed particle argument allocations.
            assertEquals(2, emptyArrays + zeroArrays);
            assertTrue(emptyArrays == 1 || emptyArrays == 2);
            assertTrue(zeroArrays == 0 || zeroArrays == 1);

            for (String className : ICE_AND_FIRE.keySet()) {
                assertEquals(className, loader.define(className, transformed.get(className)).getName());
            }
        } finally {
            loader.close();
            jar.close();
        }
    }

    private static Map<String, byte[]> transformAll(JarFile jar, Map<String, String> targets)
        throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        IceClientOptimizerTransformer transformer = new IceClientOptimizerTransformer();
        for (Map.Entry<String, String> target : targets.entrySet()) {
            byte[] original = read(jar, target.getKey());
            String fingerprint = CoreClassFingerprint.sha256(original);
            TargetSpec targetSpec = OptimizerTargetCatalog.find(target.getKey());
            assertTrue("unreviewed target fingerprint: " + target.getKey() + " @ " + fingerprint,
                target.getValue().equals(fingerprint)
                    || targetSpec != null && targetSpec.hasReviewedFingerprint(fingerprint));
            byte[] transformed = transformer.transform(target.getKey(), target.getKey(), original);
            assertFalse("target was left unchanged: " + target.getKey(), Arrays.equals(original, transformed));
            new ClassReader(transformed);
            result.put(target.getKey(), transformed);
        }
        return result;
    }

    private static void verifyQuaternionCache(Class<?> quaternion) throws Exception {
        Object value = quaternion.newInstance();
        Method matrix = quaternion.getMethod(MoBendsQuaternionAdapter.METHOD);
        FloatBuffer first = (FloatBuffer) matrix.invoke(value);
        FloatBuffer second = (FloatBuffer) matrix.invoke(value);
        assertSame(first, second);
        int[] before = new int[16];
        for (int i = 0; i < before.length; i++) before[i] = Float.floatToRawIntBits(first.get(i));
        Field x = quaternion.getField("x");
        x.setFloat(value, 0.25F);
        FloatBuffer third = (FloatBuffer) matrix.invoke(value);
        assertSame(first, third);
        boolean changed = false;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != Float.floatToRawIntBits(third.get(i))) {
                changed = true;
                break;
            }
        }
        assertTrue("matrix must be recomputed after raw quaternion bits change", changed);
    }

    private static int countCalls(byte[] bytes, final String owner, final String name) {
        final int[] count = { 0 };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(int opcode, String callOwner, String callName,
                                                String callDescriptor, boolean isInterface) {
                        if (owner.equals(callOwner) && name.equals(callName)) count[0]++;
                    }
                };
            }
        }, 0);
        return count[0];
    }

    private static int countCallsInMethod(byte[] bytes, final String targetMethod,
                                          final String targetDescriptor, final String owner,
                                          final String name) {
        final int[] count = { 0 };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                if (!targetMethod.equals(methodName) || !targetDescriptor.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(int opcode, String callOwner, String callName,
                                                String callDescriptor, boolean isInterface) {
                        if (owner.equals(callOwner) && (name == null || name.equals(callName))) count[0]++;
                    }
                };
            }
        }, 0);
        return count[0];
    }

    private static File configuredJar(String property, String message) {
        File result = optionalConfiguredJar(property);
        Assume.assumeTrue(message, result != null && result.isFile());
        return result;
    }

    private static File optionalConfiguredJar(String property) {
        String configured = System.getProperty(property, "").trim();
        return configured.isEmpty() ? null : new File(configured);
    }

    private static File siblingJar(File mainJar, final String prefix) {
        File[] matches = mainJar.getParentFile().listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isFile() && pathname.getName().toLowerCase().startsWith(prefix)
                    && pathname.getName().toLowerCase().endsWith(".jar");
            }
        });
        return matches == null || matches.length == 0 ? null : matches[0];
    }

    private static Map<String, String> targets(String[][] values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String[] value : values) result.put(value[0], value[1]);
        return result;
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull("missing class " + className, entry);
        return readFully(jar.getInputStream(entry));
    }

    private static byte[] readFully(InputStream input) throws Exception {
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

    private static final class ByteLoader extends URLClassLoader {
        private ByteLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
