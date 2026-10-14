package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class LoadedChunkSnapshotAdapterTest {
    @Test public void real8192CapacityConstructorInstallsTrackedMapAfterAutosaveAndReusesSnapshots() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "");
        Assume.assumeFalse("configure ice.minecraft.srg.jar", path.isEmpty());
        byte[] original;
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(LoadedChunkSnapshotAdapter.TARGET + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            original = out.toByteArray();
        }
        byte[] autosave = new MinecraftAutosaveAdapter().transform(LoadedChunkSnapshotAdapter.TARGET, original, null);
        final byte[] transformed = new LoadedChunkSnapshotAdapter().transform(LoadedChunkSnapshotAdapter.TARGET, autosave, null);
        assertArrayEquals(frames(autosave), frames(transformed));
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(transformed).accept(node, 0);
        int constructorCalls = 0;
        for (MethodNode method : node.methods) if ("<init>".equals(method.name))
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (LoadedChunkSnapshotAdapter.TRACKED.equals(call.owner) && "<init>".equals(call.name)) {
                    assertEquals("(I)V", call.desc); constructorCalls++;
                }
            }
        assertEquals(1, constructorCalls);
        ArrayList<URL> urls = new ArrayList<URL>(); urls.add(new File(path).toURI().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (LoadedChunkSnapshotAdapter.TARGET.replace('/', '.').equals(name)) return defineClass(name, transformed, 0, transformed.length);
                return super.findClass(name);
            }
        };
        try {
            Class<?> provider = loader.loadClass(LoadedChunkSnapshotAdapter.TARGET.replace('/', '.'));
            provider.getDeclaredMethods(); // Verify the full final class, including all inherited save frames.
            Object instance = provider.getConstructor(loader.loadClass("net.minecraft.world.WorldServer"),
                loader.loadClass("net.minecraft.world.chunk.storage.IChunkLoader"),
                loader.loadClass("net.minecraft.world.gen.IChunkGenerator")).newInstance(null, null, null);
            Object map = provider.getField("field_73244_f").get(instance);
            assertEquals(LoadedChunkSnapshotAdapter.TRACKED.replace('/', '.'), map.getClass().getName());
            Class<?> vanillaMap = loader.loadClass(LoadedChunkSnapshotAdapter.MAP.replace('/', '.'));
            Field capacity = vanillaMap.getDeclaredField("n"); capacity.setAccessible(true);
            assertEquals(capacity.getInt(vanillaMap.getConstructor(int.class).newInstance(8192)), capacity.getInt(map));
            Method put = map.getClass().getMethod("put", long.class, Object.class);
            Object view = map.getClass().getMethod("values").invoke(map);
            Method snapshot = view.getClass().getMethod("orderedSnapshot", int.class);
            put.invoke(map, 9L, null);
            Object[] first = (Object[]) snapshot.invoke(view, 1);
            assertEquals(1, first.length);
            assertSame(first, snapshot.invoke(view, 1));
            put.invoke(map, 17L, null);
            Object[] second = (Object[]) snapshot.invoke(view, 2);
            assertNotSame(first, second); assertEquals(2, second.length);
            assertSame(second, snapshot.invoke(view, 2)); assertEquals(1, first.length);
        } finally { loader.close(); }
    }
    private static String[] frames(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        ArrayList<String> result = new ArrayList<String>();
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction instanceof FrameNode) {
                FrameNode frame = (FrameNode) instruction;
                result.add(method.name + method.desc + ':' + frame.local + '/' + frame.stack);
            }
        return result.toArray(new String[result.size()]);
    }
}
