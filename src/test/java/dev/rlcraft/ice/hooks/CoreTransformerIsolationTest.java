package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.save.PendingTickAccessor;
import dev.rlcraft.ice.optimizer.lock.ClassFingerprint;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Regression coverage for the classes available during the early CoreMod phase. */
public class CoreTransformerIsolationTest {
    private static final String PENDING_TICK_ACCESSOR =
        "dev.rlcraft.ice.optimizer.compat.save.PendingTickAccessor";
    private static final String CHUNK_BUFFER_ACCESSOR =
        "dev.rlcraft.ice.optimizer.compat.chunk.ChunkBufferAccessor";
    private static final String CHUNK_VBO_ACCESSOR =
        "dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor";

    @Test
    public void optimizerCoreInitializesWithoutTheMainRuntime() throws Exception {
        URL hooksClasses = IceOptimizerTransformer.class.getProtectionDomain()
            .getCodeSource().getLocation();
        URL earlyAbiClasses = PendingTickAccessor.class.getProtectionDomain()
            .getCodeSource().getLocation();
        assertEquals(earlyAbiClasses, ChunkBufferAccessor.class.getProtectionDomain()
            .getCodeSource().getLocation());
        assertEquals(earlyAbiClasses, ChunkVertexBufferAccessor.class.getProtectionDomain()
            .getCodeSource().getLocation());
        URL targetResource = IceOptimizerTransformer.class
            .getResource("/optimizer-targets.properties");
        List<URL> roots = new ArrayList<URL>();
        roots.add(hooksClasses);
        if (!earlyAbiClasses.equals(hooksClasses)) roots.add(earlyAbiClasses);
        if (targetResource != null) {
            String external = targetResource.toExternalForm();
            String suffix = "optimizer-targets.properties";
            if (external.endsWith(suffix)) {
                roots.add(new URL(external.substring(0, external.length() - suffix.length())));
            }
        }

        IsolatedHooksLoader loader = new IsolatedHooksLoader(
            roots.toArray(new URL[roots.size()]), getClass().getClassLoader());
        try {
            Class<?> accessor = Class.forName(PENDING_TICK_ACCESSOR, true, loader);
            assertEquals("the transformed-class ABI must be defined by the isolated core loader",
                loader, accessor.getClassLoader());
            assertEquals(4, accessor.getDeclaredMethods().length);
            Class<?> chunkBuffer = Class.forName(CHUNK_BUFFER_ACCESSOR, true, loader);
            Class<?> chunkVbo = Class.forName(CHUNK_VBO_ACCESSOR, true, loader);
            assertEquals(loader, chunkBuffer.getClassLoader());
            assertEquals(loader, chunkVbo.getClassLoader());
            assertEquals(4, chunkBuffer.getDeclaredMethods().length);
            assertEquals(5, chunkVbo.getDeclaredMethods().length);

            String target = "com.dhanantry.scapeandrunparasites.client.model.entity.pure.ModelEsor";
            byte[] unknown = emptyClass(target.replace('.', '/'));

            Class<?> clientType = Class.forName(
                "dev.rlcraft.ice.hooks.IceClientOptimizerTransformer", true, loader);
            Object client = clientType.newInstance();
            Method optimize = clientType.getMethod(
                "transform", String.class, String.class, byte[].class);
            assertArrayEquals(unknown, (byte[]) optimize.invoke(client, target, target, unknown));
            assertEquals("early patch journaling must not probe the main runtime", 0,
                loader.optimizerRuntimeLoadAttempts());

            Class<?> sharedType = Class.forName(
                "dev.rlcraft.ice.hooks.IceOptimizerTransformer", true, loader);
            Object shared = sharedType.newInstance();
            Method sharedOptimize = sharedType.getMethod(
                "transform", String.class, String.class, byte[].class);
            assertArrayEquals(unknown,
                (byte[]) sharedOptimize.invoke(shared, target, target, unknown));

            Class<?> journalType = Class.forName(
                "dev.rlcraft.ice.hooks.OptimizerPatchJournal", true, loader);
            journalType.getMethod("replay").invoke(null);
            assertTrue("explicit runtime replay should attempt the late-bound registry link",
                loader.optimizerRuntimeLoadAttempts() > 0);

            Class<?> pluginType = Class.forName(
                "dev.rlcraft.ice.hooks.IceOptimizerLoadingPlugin", true, loader);
            Object plugin = pluginType.newInstance();
            String[] transformers = (String[]) pluginType
                .getMethod("getASMTransformerClass").invoke(plugin);
            assertEquals(1, transformers.length);
            assertEquals("dev.rlcraft.ice.hooks.IceOptimizerTransformer", transformers[0]);
            IFMLLoadingPlugin.TransformerExclusions exclusions = pluginType.getAnnotation(
                IFMLLoadingPlugin.TransformerExclusions.class);
            assertArrayEquals(new String[] { "dev.rlcraft.ice.hooks." }, exclusions.value());
        } finally {
            loader.close();
        }
    }

    @Test
    public void standaloneCoreFingerprintStaysInSyncWithTheMainAbi() {
        byte[] sample = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(ClassFingerprint.sha256(sample), CoreClassFingerprint.sha256(sample));
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName,
            null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class IsolatedHooksLoader extends URLClassLoader {
        private int optimizerRuntimeLoadAttempts;

        private IsolatedHooksLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
            if (name.startsWith("dev.rlcraft.ice.hooks.")
                || PENDING_TICK_ACCESSOR.equals(name)
                || CHUNK_BUFFER_ACCESSOR.equals(name)
                || CHUNK_VBO_ACCESSOR.equals(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            if (name.startsWith("dev.rlcraft.ice.optimizer.")) {
                optimizerRuntimeLoadAttempts++;
                throw new ClassNotFoundException("main runtime intentionally hidden: " + name);
            }
            return super.loadClass(name, resolve);
        }

        private int optimizerRuntimeLoadAttempts() {
            return optimizerRuntimeLoadAttempts;
        }
    }
}
