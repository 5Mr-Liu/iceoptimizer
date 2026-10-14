package dev.rlcraft.ice.hooks;

import java.io.*;
import java.net.*;
import java.util.jar.*;
import org.junit.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import static org.junit.Assert.*;

public class WorldgenRemainingAdaptersTest {
    @Test public void preparationVerifiesOnlyTwoRegistrationBoundaries() throws Exception {
        String name = "com.pg85.otg.customobjects.CustomObjectCollection";
        byte[] source = read("ice.otg.jar", name);
        byte[] result = new OtgObjectPreparationAdapter().transform(name, source, null);
        verify(result); assertEquals(2, calls(result, "prepare")); define(name, result).getDeclaredMethods();
    }
    @Test public void columnsVerifyRealJarAndKeepGeneration() throws Exception {
        String name = "com.pg85.otg.forge.generator.OTGChunkGenerator";
        byte[] source = read("ice.otg.jar", name);
        byte[] result = new OtgColumnQueryAdapter().transform(name, source, null);
        verify(result);
        assertEquals(calls(source, "generate"), calls(result, "generate"));
        assertEquals(calls(source, "toChunk"), calls(result, "toChunk"));
        define(name, result).getDeclaredMethods();
    }
    @Test public void bo3RetainsWorldCallsAndVerifiesRealJar() throws Exception {
        String name = "com.pg85.otg.customobjects.bo3.BO3";
        byte[] source = read("ice.otg.jar", name);
        byte[] result = new OtgBo3ExecutionAdapter().transform(name, source, null);
        verify(result);
        for (String method : new String[] { "getMaterial", "isLoaded", "fireCanCustomObjectSpawnEvent" })
            assertEquals(method, calls(source, method), calls(result, method));
        assertEquals(calls(source, "getBlocks") - 1, calls(result, "getBlocks"));
        assertEquals(1, calls(result, "begin"));
        assertTrue(calls(result, "release") > 1);
        define(name, result).getDeclaredMethods();
    }
    @Test public void recurrentMethodsVerifyRealJar() throws Exception {
        for (String name : new String[] { "ivorius.reccomplex.world.gen.feature.structure.MapGenStructureHook",
                "ivorius.reccomplex.events.handlers.RCTerrainGenEventHandler" }) {
            byte[] source = read("ice.recurrent.jar", name);
            byte[] result = new RecurrentReflectionAdapter().transform(name, source, null);
            verify(result);
            RecurrentReflectionAdapterTest.verifyRealClass(name, result);
            assertEquals(calls(source, "invoke"), calls(result, "invoke"));
            assertEquals(0, calls(result, "findMethod"));
            assertEquals(0, calls(result, "getDeclaredMethod"));
        }
    }
    static byte[] read(String property, String name) throws Exception {
        String path = System.getProperty(property, ""); Assume.assumeTrue(!path.isEmpty());
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    static void verify(byte[] bytes) throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
    }
    static int calls(byte[] bytes, String name) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int count = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction instanceof MethodInsnNode && name.equals(((MethodInsnNode) instruction).name)) count++;
        return count;
    }
    private static Class<?> define(String name, byte[] bytes) {
        return new ClassLoader(WorldgenRemainingAdaptersTest.class.getClassLoader()) {
            Class<?> load() { return defineClass(name, bytes, 0, bytes.length); }
        }.load();
    }
}
