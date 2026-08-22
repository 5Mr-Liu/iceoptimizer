package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.lock.ClassFingerprint;
import dev.rlcraft.ice.optimizer.compat.save.PendingTickAccessor;
import dev.rlcraft.ice.profiler.probe.ProbeIds;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CoreTransformerIsolationTest {
    private static final String PENDING_TICK_ACCESSOR =
        "dev.rlcraft.ice.optimizer.compat.save.PendingTickAccessor";
    private static final String[] TERRAIN_VISIBILITY_ABI = {
        "dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor",
        "dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor",
        "dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderChunkIndexAccessor",
        "dev.rlcraft.ice.optimizer.compat.chunk.TerrainCompiledChunkAccessor",
        "dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityMaskAccessor"
    };
    private static final String EARLY_GL_TRACKER =
        "dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker";
    private static final String EARLY_MATRIX_TRACKER =
        "dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker";
    private static final String ICE_AND_FIRE_RAW_NODE_ACCESSOR =
        "dev.rlcraft.ice.optimizer.compat.iceandfire.IceAndFireRawNodeAccessor";
    private static final String PARTICLE_RENDER_ACCESS =
        "dev.rlcraft.ice.optimizer.compat.particle.ParticleRenderAccess";

    @Test
    public void coreTransformersInitializeAndRunWithoutMainRuntimeClasses() throws Exception {
        URL classes = IceProfilerTransformer.class.getProtectionDomain().getCodeSource().getLocation();
        URL earlyAbiClasses = PendingTickAccessor.class.getProtectionDomain().getCodeSource().getLocation();
        URL targetResource = IceProfilerTransformer.class.getResource("/optimizer-targets.properties");
        List<URL> roots = new ArrayList<URL>();
        roots.add(classes);
        if (!earlyAbiClasses.equals(classes)) roots.add(earlyAbiClasses);
        if (targetResource != null) {
            String external = targetResource.toExternalForm();
            String suffix = "optimizer-targets.properties";
            if (external.endsWith(suffix)) roots.add(new URL(external.substring(0, external.length() - suffix.length())));
        }

        IsolatedHooksLoader loader = new IsolatedHooksLoader(roots.toArray(new URL[roots.size()]), getClass().getClassLoader());
        try {
            Class<?> accessor = Class.forName(PENDING_TICK_ACCESSOR, true, loader);
            assertEquals("the transformed-class ABI must be defined by the isolated core loader",
                loader, accessor.getClassLoader());
            assertEquals(4, accessor.getDeclaredMethods().length);
            int[] expectedMethods = { 11, 6, 5, 2, 1 };
            for (int i = 0; i < TERRAIN_VISIBILITY_ABI.length; i++) {
                Class<?> visibilityAbi = Class.forName(TERRAIN_VISIBILITY_ABI[i], true, loader);
                assertEquals("visibility ABI must be defined by the isolated core loader",
                    loader, visibilityAbi.getClassLoader());
                assertEquals(expectedMethods[i], visibilityAbi.getDeclaredMethods().length);
            }
            Class<?> glTracker = Class.forName(EARLY_GL_TRACKER, true, loader);
            assertEquals(loader, glTracker.getClassLoader());
            Class<?> matrixTracker = Class.forName(EARLY_MATRIX_TRACKER, true,
                loader);
            assertEquals(loader, matrixTracker.getClassLoader());
            matrixTracker.getMethod("matrixMode", int.class).invoke(null, 5888);

            byte[] managerBytes = new GlStateTrackingAdapter(
                GlStateTrackingAdapter.Part.GL_STATE_MANAGER).transform(
                    GlStateTrackingAdapter.GL_STATE_MANAGER,
                    GlStateTrackingAdapterTest.syntheticManager(
                        GlStateTrackingAdapter.GL_STATE_MANAGER),
                    new TargetSpec(GlStateTrackingAdapter.GL_STATE_MANAGER,
                        "modern-visibility-hzb", "test",
                        Collections.<String>emptySet()));
            Class<?> manager = loader.defineTarget(managerBytes);
            manager.getMethod("func_179128_n", int.class).invoke(null, 5888);
            Class<?> matrixState = Class.forName(EARLY_MATRIX_TRACKER + "$State",
                false, loader);
            assertEquals(loader, matrixState.getClassLoader());
            assertEquals(0L, ((Long) matrixTracker.getMethod("invalidations")
                .invoke(null)).longValue());
            Class<?> iceAndFireAccessor = Class.forName(
                ICE_AND_FIRE_RAW_NODE_ACCESSOR, true, loader);
            assertEquals("Ice and Fire transformed-class ABI must be defined by the core loader",
                loader, iceAndFireAccessor.getClassLoader());
            assertEquals(1, iceAndFireAccessor.getDeclaredMethods().length);
            Class<?> particleAccess = Class.forName(PARTICLE_RENDER_ACCESS,
                true, loader);
            assertEquals("particle transformed-class ABI must be defined by the core loader",
                loader, particleAccess.getClassLoader());
            assertEquals(16, particleAccess.getDeclaredMethods().length);

            Class<?> profilerType = Class.forName("dev.rlcraft.ice.hooks.IceProfilerTransformer", true, loader);
            Object profiler = profilerType.newInstance();
            Method transform = profilerType.getMethod("transform", String.class, String.class, byte[].class);
            byte[] world = syntheticWorld();
            byte[] profiled = (byte[]) transform.invoke(profiler, "amu", "net.minecraft.world.World", world);
            assertNotEquals(world.length, profiled.length);
            assertTrue(new String(profiled, StandardCharsets.ISO_8859_1).contains("ProbeBridge"));

            Class<?> optimizerType = Class.forName("dev.rlcraft.ice.hooks.IceClientOptimizerTransformer", true, loader);
            Object optimizer = optimizerType.newInstance();
            Method optimize = optimizerType.getMethod("transform", String.class, String.class, byte[].class);
            String target = "com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelEsor";
            byte[] unknown = emptyClass(target.replace('.', '/'));
            assertArrayEquals(unknown, (byte[]) optimize.invoke(optimizer, target, target, unknown));
            assertEquals("early patch journaling must not probe the main runtime", 0,
                loader.optimizerRuntimeLoadAttempts());

            Class<?> sharedOptimizerType = Class.forName("dev.rlcraft.ice.hooks.IceOptimizerTransformer", true, loader);
            Object sharedOptimizer = sharedOptimizerType.newInstance();
            Method sharedOptimize = sharedOptimizerType.getMethod("transform", String.class, String.class, byte[].class);
            assertArrayEquals(unknown, (byte[]) sharedOptimize.invoke(sharedOptimizer, target, target, unknown));

            Class<?> journalType = Class.forName("dev.rlcraft.ice.hooks.OptimizerPatchJournal", true, loader);
            journalType.getMethod("replay").invoke(null);
            assertTrue("explicit runtime replay should attempt the late-bound registry link",
                loader.optimizerRuntimeLoadAttempts() > 0);

            Class<?> pluginType = Class.forName("dev.rlcraft.ice.hooks.IceProfilerLoadingPlugin", true, loader);
            Object plugin = pluginType.newInstance();
            String[] transformers = (String[]) pluginType.getMethod("getASMTransformerClass").invoke(plugin);
            assertEquals(1, transformers.length);
            assertEquals("dev.rlcraft.ice.hooks.IceProfilerTransformer", transformers[0]);

            Class<?> optimizerPluginType = Class.forName("dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin", true, loader);
            Object optimizerPlugin = optimizerPluginType.newInstance();
            String[] optimizerTransformers = (String[]) optimizerPluginType.getMethod("getASMTransformerClass").invoke(optimizerPlugin);
            assertEquals(1, optimizerTransformers.length);
            assertEquals("dev.rlcraft.ice.hooks.IceOptimizerTransformer", optimizerTransformers[0]);
            IFMLLoadingPlugin.TransformerExclusions exclusions = optimizerPluginType.getAnnotation(
                IFMLLoadingPlugin.TransformerExclusions.class);
            assertArrayEquals(new String[] { "dev.rlcraft.ice.hooks." }, exclusions.value());
        } finally {
            loader.close();
        }
    }

    @Test
    public void standaloneCoreCopiesStayInSyncWithTheMainAbi() {
        byte[] sample = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(ClassFingerprint.sha256(sample), CoreClassFingerprint.sha256(sample));
        assertEquals(ProbeIds.ENTITY_TICK, ProbeProtocol.ENTITY_TICK);
        assertEquals(ProbeIds.TILE_ENTITY_TICK, ProbeProtocol.TILE_ENTITY_TICK);
        assertEquals(ProbeIds.EVENT_HANDLER, ProbeProtocol.EVENT_HANDLER);
        assertEquals(ProbeIds.CHUNK_GENERATION, ProbeProtocol.CHUNK_GENERATION);
        assertEquals(ProbeIds.CHUNK_SAVE, ProbeProtocol.CHUNK_SAVE);
        assertEquals(ProbeIds.CHUNK_RENDER, ProbeProtocol.CHUNK_RENDER);
    }

    private static byte[] syntheticWorld() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "amu", null, "java/lang/Object", null);
        addConstructor(writer);
        MethodVisitor target = writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(Lvg;Z)V", null, null);
        target.visitCode();
        target.visitInsn(Opcodes.RETURN);
        target.visitMaxs(0, 3);
        target.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        addConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
    }

    private static final class IsolatedHooksLoader extends URLClassLoader {
        private int optimizerRuntimeLoadAttempts;

        private IsolatedHooksLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.rlcraft.ice.hooks.") || isEarlyAbi(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            if (name.startsWith("dev.rlcraft.ice.optimizer.")) {
                optimizerRuntimeLoadAttempts++;
                throw new ClassNotFoundException("main runtime intentionally hidden: " + name);
            }
            if (name.startsWith("dev.rlcraft.ice.profiler.")) {
                throw new ClassNotFoundException("main runtime intentionally hidden: " + name);
            }
            return super.loadClass(name, resolve);
        }

        private int optimizerRuntimeLoadAttempts() {
            return optimizerRuntimeLoadAttempts;
        }

        private static boolean isEarlyAbi(String name) {
            if (PENDING_TICK_ACCESSOR.equals(name)) return true;
            if (name.equals(EARLY_GL_TRACKER)
                || name.startsWith(EARLY_GL_TRACKER + "$")) return true;
            if (name.equals(EARLY_MATRIX_TRACKER)
                || name.startsWith(EARLY_MATRIX_TRACKER + "$")) return true;
            if (name.equals(ICE_AND_FIRE_RAW_NODE_ACCESSOR)) return true;
            if (name.equals(PARTICLE_RENDER_ACCESS)) return true;
            for (String candidate : TERRAIN_VISIBILITY_ABI) {
                if (candidate.equals(name)) return true;
            }
            return false;
        }

        private Class<?> defineTarget(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
