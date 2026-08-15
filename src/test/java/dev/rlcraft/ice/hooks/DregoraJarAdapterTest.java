package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;

/** Optional raw-JAR call-graph verification using a Dregora reference installation. */
public class DregoraJarAdapterTest {
    private static final JarSamples[] GROUPS = {
        group("ice.dregora.srp.jar", new String[][] {
            { "com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelEsor", "221abd73737d0d280b3f77239fe4eb3a22ae0213071b698d3940816fc8d15353" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.inborn.ModelMudo", "7488572ff8663506ffdff4256e3ce8804cd9f1d69fd3005fc9a4f53f64bee8fe" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.inborn.ModelNuuh", "017b27a836b582d0f801266640a72f4e599ed34fc799623797e002972beddbf4" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.pure.preeminent.ModelJinjo", "08357941a890d9ee15c2378507d83fa0da824e5f8b14b9ec2cbff15c89c8a651" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.adapted.ModelBanoAdapted", "b9c0c0c8141354ed32914dc811bf84cc24ebde33c07070122b3e505839930607" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfVillager", "8e4e24b21f34016ef6ffac2d7a92510ac8544dc1eb16f818ccdc4b127118f7da" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfEnderman", "a6a80b8aace403ab9d3aedc9883c3adfdabf4c380c3bb64deed6f1b95cfdf6b1" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfHorse", "2f3527fd9a7fb15b4386a152a15a53394db8313db8ff038c923e0dc4cb2fc85b" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.infected.ModelInfHuman", "15357a37431a6be19e16e9a71786136de26209ee7987de054c677dc06e46c374" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.crude.ModelCruxA", "667c20a2584428b7e196da302fa06d33054fae49b22652f34b244795b15f10e5" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelAlafha", "052f5f01e34b639825636bea67c727d891d2691df89e379d084d1dc4c3efdb5f" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.primitive.ModelNogla", "62ef54a3dd161249a0af711963fbfc64e52ad005b74484e5e682b036236ed388" },
            { "com.dhanantry.scapeandrunparasites.client.model.entity.derived.ModelKirin", "4fada3c814c5c9632afea5af4860f71c40d51916f5bce7e0e3aae75d95807e95" },
            { "com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityParasiteBase", "ebe7d0bb9a5799e65fa624745fea1798791ac20ca625dbcd6e88686e365114e8" },
            { "com.dhanantry.scapeandrunparasites.entity.ai.EntityAINearestAttackableTargetStatus", "016aa631df724f120e202b0b6178ef21c520b89937f44cc2990e1c10e9a34296" }
        }),
        group("ice.dregora.lycanites.jar", new String[][] {
            { "com.lycanitesmobs.ObjectManager", "6123bc6276d62167a22a705ab0208dad69a0e9aa1e640f32d432fd02415ec6d2" },
            { "com.lycanitesmobs.PotionEffects", "cb1d77b43a151165617a2c202c712b6418279075ecc1fe85f02bc08d7aff0ab9" }
        }),
        group("ice.dregora.renderlib.jar", new String[][] {
            { "meldexun.renderlib.util.TileEntityUtil", "4bba08578e856b5a43adb1371895eea56ffbd0ed850c1877777eb841167c4125" }
        }),
        group("ice.dregora.foliage.jar", new String[][] {
            { "mods.octarinecore.client.render.AoFaceData", "a41f971b1a12225f37cba122a456bdcaa46f67025d8fc757a917697deb775579" }
        }),
        group("ice.dregora.iceandfire.jar", new String[][] {
            { "com.github.alexthe666.iceandfire.client.model.animator.IceAndFireTabulaModelAnimator", "572679de21b7b9f6f17cbd0f53eca956fbded5f4de4900adf66686111aa65aa1" },
            { "com.github.alexthe666.iceandfire.entity.EntitySeaSerpent", "26bb05701a6723db7715809218bdc57bc4aea1efb1f77d807376b53a6e8a69e9" }
        })
    };

    @Test
    public void allConfiguredDregoraJarsMatchReviewedAdapterGraphs() throws Exception {
        boolean configuredAny = false;
        List<String> failures = new ArrayList<String>();
        for (JarSamples group : GROUPS) {
            String configured = System.getProperty(group.property, "").trim();
            if (configured.isEmpty()) continue;
            configuredAny = true;
            File file = new File(configured);
            Assume.assumeTrue(file.isFile());
            JarFile jar = new JarFile(file);
            try {
                for (Sample sample : group.samples) verify(jar, sample, failures);
            } finally {
                jar.close();
            }
        }
        Assume.assumeTrue("configure at least one Dregora JAR", configuredAny);
        org.junit.Assert.assertTrue("Dregora raw-JAR adapter failures: " + failures, failures.isEmpty());
    }

    private static void verify(JarFile jar, Sample sample, List<String> failures) {
        try {
            byte[] original = read(jar, sample.className);
            org.junit.Assert.assertEquals(sample.sha256, CoreClassFingerprint.sha256(original));
            TargetSpec catalog = OptimizerTargetCatalog.find(sample.className);
            assertNotNull("missing catalog target " + sample.className, catalog);
            org.junit.Assert.assertTrue("Dregora raw hash is not whitelisted for " + sample.className,
                catalog.accepts(sample.sha256));
            OptimizerBytecodeAdapter adapter = OptimizerAdapterRegistry.find(catalog.adapterId);
            assertNotNull("missing adapter " + catalog.adapterId, adapter);
            TargetSpec target = new TargetSpec(sample.className, catalog.moduleId, catalog.adapterId,
                Collections.singleton(sample.sha256));
            byte[] transformed = adapter.transform(sample.className, original, target);
            assertNotNull(transformed);
            assertFalse(Arrays.equals(original, transformed));
            new ClassReader(transformed);
        } catch (Throwable error) {
            failures.add(sample.className + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull("missing class " + className, entry);
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

    private static JarSamples group(String property, String[][] values) {
        Sample[] samples = new Sample[values.length];
        for (int i = 0; i < values.length; i++) samples[i] = new Sample(values[i][0], values[i][1]);
        return new JarSamples(property, samples);
    }

    private static final class JarSamples {
        private final String property;
        private final Sample[] samples;
        private JarSamples(String property, Sample[] samples) { this.property = property; this.samples = samples; }
    }

    private static final class Sample {
        private final String className;
        private final String sha256;
        private Sample(String className, String sha256) { this.className = className; this.sha256 = sha256; }
    }
}
