package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/** Test-only OptiFine patch plus the LaunchWrapper notch-to-SRG remap stage. */
final class OptifinePatchedClassSupport implements Closeable {
    private final URLClassLoader loader;
    private final JarFile client;
    private final Object transformer;
    private final Method transform;
    private final SimpleRemapper remapper;

    static OptifinePatchedClassSupport openOrSkip() throws Exception {
        String optifine = System.getProperty("ice.optifine.jar", "").trim();
        String client = System.getProperty("ice.minecraft.client.jar", "").trim();
        String mappings = System.getProperty("ice.notch.srg", "").trim();
        Assume.assumeTrue("run with -PoptifineJar, -PminecraftClientJar and -PnotchSrg",
            !optifine.isEmpty() && !client.isEmpty() && !mappings.isEmpty());
        Assume.assumeTrue(new File(optifine).isFile());
        Assume.assumeTrue(new File(client).isFile());
        Assume.assumeTrue(new File(mappings).isFile());
        return new OptifinePatchedClassSupport(new File(optifine),
            new File(client), new File(mappings));
    }

    private OptifinePatchedClassSupport(File optifine, File clientJar,
                                        File mappings) throws Exception {
        loader = new URLClassLoader(new URL[] {optifine.toURI().toURL(),
            clientJar.toURI().toURL()}, getClass().getClassLoader());
        client = new JarFile(clientJar);
        Class<?> type = Class.forName("optifine.OptiFineClassTransformer", true, loader);
        transformer = type.newInstance();
        transform = type.getMethod("transform", String.class, String.class, byte[].class);
        remapper = new SimpleRemapper(readMappings(mappings));
    }

    byte[] patchAndRemap(String obfuscatedName, String transformedName)
        throws Exception {
        byte[] original = read(client, obfuscatedName);
        byte[] patched = (byte[]) transform.invoke(transformer, obfuscatedName,
            transformedName, original);
        assertNotNull(patched);
        ClassReader reader = new ClassReader(patched);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), ClassReader.EXPAND_FRAMES);
        byte[] remapped = writer.toByteArray();
        new ClassReader(remapped);
        return remapped;
    }

    @Override public void close() throws java.io.IOException {
        java.io.IOException failure = null;
        try { client.close(); }
        catch (java.io.IOException error) { failure = error; }
        try { loader.close(); }
        catch (java.io.IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static Map<String, String> readMappings(File file) throws Exception {
        Map<String, String> mappings = new HashMap<String, String>(65536);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("CL: ")) {
                    String[] parts = line.substring(4).split(" ");
                    if (parts.length == 2) mappings.put(parts[0], parts[1]);
                } else if (line.startsWith("FD: ")) {
                    String[] parts = line.substring(4).split(" ");
                    if (parts.length == 2) mappings.put(fieldKey(parts[0]),
                        simpleName(parts[1]));
                } else if (line.startsWith("MD: ")) {
                    String[] parts = line.substring(4).split(" ");
                    if (parts.length == 4) mappings.put(methodKey(parts[0], parts[1]),
                        simpleName(parts[2]));
                }
            }
        } finally {
            reader.close();
        }
        return mappings;
    }

    private static String fieldKey(String qualified) {
        int split = qualified.lastIndexOf('/');
        return qualified.substring(0, split) + '.' + qualified.substring(split + 1);
    }

    private static String methodKey(String qualified, String descriptor) {
        return fieldKey(qualified) + descriptor;
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('/') + 1);
    }

    private static byte[] read(JarFile jar, String owner) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
