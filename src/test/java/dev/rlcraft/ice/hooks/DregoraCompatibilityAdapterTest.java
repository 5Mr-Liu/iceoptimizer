package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;

/** Optional regression samples captured from one RLCraft Dregora installation. */
public class DregoraCompatibilityAdapterTest {
    private static final Sample[] SAMPLES = {
        sample("meldexun.renderlib.util.TileEntityUtil", "renderlib-visibility",
            "renderlib-visibility-cache", "b3fc37d5013c3f0e471a1ca2ac249853a0e5d39698251acd6567c70f4091d988"),
        sample("com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityParasiteBase",
            "srp-path-node-cache", "srp-parasite-navigator",
            "714e7baa83e64bde5e5db642a429b0ff004b51194c517c0a949cfdea5b03eeac"),
        sample("com.lycanitesmobs.PotionEffects", "lycanites-effect-cache",
            "lycanites-effect-slots", "d16d1360f9866edc68077b9579d6ea6180135c5e17d6a2d1d5ae306c2bc077d4"),
        sample("com.lycanitesmobs.client.model.ModelCreatureObj", "lycanites-model-animation",
            "lycanites-lowercase-cache", "21b488f59b30337ee716266596a4330c09e661fd62816ea819b9799ab879d0b1"),
        sample("com.lycanitesmobs.ObjectManager", "lycanites-registry-lookup",
            "lycanites-registry-single-probe", "ed4c25fb53798ba4364a743f87ab565cc70ed623de91f60be1a80f9adbc8deb5"),
        sample("com.lycanitesmobs.client.model.ModelItemBase", "lycanites-model-animation",
            "lycanites-lowercase-cache", "1c4f59682dde1e20ff3fd804e8e7fba8bbf01350344af2f1db44527a19d2cbd3"),
        sample("com.lycanitesmobs.client.obj.TessellatorModel", "lycanites-obj-render",
            "lycanites-obj-display-list", "fb759b85f4db9661e58afc87347b2b50095648a92ae25ae53ec2659e1b5c445a"),
        sample("com.lycanitesmobs.client.obj.VBOModel", "lycanites-obj-render",
            "lycanites-obj-display-list", "c0bb3b7d99617a9f81edcadc614fb1b539446573e1a82df161f1ec9053461972"),
        sample("com.lycanitesmobs.client.model.ModelObjOld", "lycanites-model-animation",
            "lycanites-lowercase-cache", "f4cc29b7ab04994334dd3feb5c3c7f531d216780a524c56d82f163327dc4137b"),
        sample("com.lycanitesmobs.client.model.Animator", "lycanites-model-animation",
            "lycanites-animator-identities", "19591aea4099a296b12c8a0261faea53075c3f0a174e72844f1c01e2ea316fe6"),
        sample("com.lycanitesmobs.client.model.ModelObjPart", "lycanites-model-animation",
            "lycanites-part-indexed", "a211a9a3a7d716e6c5f3c17ef891cecece86eca6252cb1bf8af6387b76b48536")
    };

    @Test
    public void transformsAllCapturedDregoraTargets() throws Exception {
        String configured = System.getProperty("ice.dregora.discoveryDir", "").trim();
        Assume.assumeTrue("run with -PdregoraDiscoveryDir=<ice-optimizer/discovery>", !configured.isEmpty());
        File directory = new File(configured);
        Assume.assumeTrue(directory.isDirectory());

        List<String> failures = new ArrayList<String>();
        for (Sample sample : SAMPLES) {
            try {
                File input = new File(directory, sample.fileName());
                assertNotNull("missing Dregora sample " + input, input.isFile() ? input : null);
                byte[] original = Files.readAllBytes(input.toPath());
                String actual = CoreClassFingerprint.sha256(original);
                org.junit.Assert.assertEquals(sample.sha256, actual);
                TargetSpec catalog = OptimizerTargetCatalog.find(sample.className);
                assertNotNull("missing catalog target " + sample.className, catalog);
                assertTrue("Dregora runtime hash is not whitelisted for " + sample.className,
                    catalog.accepts(sample.sha256));
                assertTrue("wrong module for " + sample.className, catalog.moduleIds.contains(sample.moduleId));
                assertEquals("wrong adapter for " + sample.className, sample.adapterId, catalog.adapterId);
                OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(sample.adapterId);
                assertNotNull("missing adapter " + sample.adapterId, adapter);
                byte[] transformed = adapter.transform(sample.className, original, catalog);
                assertNotNull("adapter returned null for " + sample.className, transformed);
                assertFalse("adapter left Dregora target unchanged: " + sample.className,
                    Arrays.equals(original, transformed));
                new ClassReader(transformed);
            } catch (Throwable error) {
                failures.add(sample.className + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
        org.junit.Assert.assertTrue("Dregora adapter failures: " + failures, failures.isEmpty());
    }

    @Test
    public void transformedDregoraClassesPassTheRealJvmVerifier() throws Exception {
        String discovery = System.getProperty("ice.dregora.discoveryDir", "").trim();
        String mods = System.getProperty("ice.dregora.modsDir", "").trim();
        Assume.assumeTrue("run with Dregora discovery and mods directories",
            !discovery.isEmpty() && !mods.isEmpty());
        File directory = new File(discovery);
        File modsDirectory = new File(mods);
        Assume.assumeTrue(directory.isDirectory() && modsDirectory.isDirectory());

        File[] jars = modsDirectory.listFiles(new FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".jar");
            }
        });
        assertNotNull("unable to enumerate Dregora mod JARs", jars);
        Arrays.sort(jars);
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toURI().toURL();

        URLClassLoader dependencies = new URLClassLoader(urls, getClass().getClassLoader());
        try {
            for (Sample sample : SAMPLES) verifyClass(directory, dependencies, sample);
        } finally {
            dependencies.close();
        }
    }

    private static void verifyClass(File directory, ClassLoader dependencies, Sample sample) throws Exception {
        String className = sample.className;
        byte[] original = Files.readAllBytes(new File(directory, sample.fileName()).toPath());
        TargetSpec target = OptimizerTargetCatalog.find(className);
        assertNotNull("missing OBJ target " + className, target);
        byte[] transformed = OptimizerAdapterRegistry.find(sample.adapterId)
            .transform(className, original, target);
        Class<?> defined = new ByteLoader(dependencies).define(className, transformed);
        assertEquals(className, defined.getName());
        // Reflection forces HotSpot to finish method verification and resolve descriptor types.
        defined.getDeclaredMethods();
        defined.getDeclaredConstructors();
    }

    private static Sample sample(String className, String moduleId, String adapterId, String sha256) {
        return new Sample(className, moduleId, adapterId, sha256);
    }

    private static final class Sample {
        private final String className;
        private final String moduleId;
        private final String adapterId;
        private final String sha256;

        private Sample(String className, String moduleId, String adapterId, String sha256) {
            this.className = className;
            this.moduleId = moduleId;
            this.adapterId = adapterId;
            this.sha256 = sha256;
        }

        private String fileName() {
            return className.replace('.', '_').replace('$', '_') + '-' + sha256.substring(0, 16) + ".class";
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
