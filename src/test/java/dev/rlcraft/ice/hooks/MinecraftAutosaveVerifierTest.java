package dev.rlcraft.ice.hooks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;

/** JVM verification includes the invocation-local intent's extended original stack maps. */
public class MinecraftAutosaveVerifierTest {
    @Test public void realServerWorldAndProviderStackMapsVerifyInTheJvm() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "");
        Assume.assumeFalse(path.isEmpty());
        for (final String owner : new String[] { MinecraftAutosaveAdapter.SERVER, MinecraftAutosaveAdapter.WORLD,
                                                MinecraftAutosaveAdapter.PROVIDER }) {
            byte[] original;
            try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n); original = out.toByteArray();
            }
            final byte[] transformed = new MinecraftAutosaveAdapter().transform(owner.replace('/', '.'), original, null);
            ArrayList<URL> urls = new ArrayList<URL>(); urls.add(new File(path).toURI().toURL());
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
            URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), null) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (owner.replace('/', '.').equals(name)) return defineClass(name, transformed, 0, transformed.length);
                    return super.findClass(name);
                }
            };
            try { loader.loadClass(owner.replace('/', '.')).getDeclaredMethods(); }
            finally { loader.close(); }
        }
    }
}
