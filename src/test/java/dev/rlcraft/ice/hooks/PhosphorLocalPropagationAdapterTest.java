package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public class PhosphorLocalPropagationAdapterTest {
    @Test public void actualJarPreservesPackedDecodeAndReusesCoordinatesForLightAndStorageCalls() throws Exception {
        ClassNode node = transformed();
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0)
            new Analyzer(new BasicVerifier()).analyze(node.name, method);
        MethodNode fetch = method(node, PhosphorLocalPropagationAdapter.FETCH);
        MethodNode old = method(node, PhosphorLocalPropagationAdapter.ORIGINAL);
        assertEquals(1, calls(fetch, node.name, "decodeWorldCoord"));
        assertEquals(1, calls(old, node.name, "decodeWorldCoord"));
        for (AbstractInsnNode instruction : fetch.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                assertFalse("neighbor coordinates must come from the packed key",
                    field.owner.equals(node.name) && field.name.equals("curPos"));
            }
        }
        assertEquals(1, calls(fetch, "net/minecraft/world/chunk/Chunk", "func_76587_i"));
        assertEquals(1, calls(fetch, node.name, "getChunk"));
        MethodNode local = method(node, PhosphorLocalPropagationAdapter.LOCAL_LIGHT);
        assertEquals(0, calls(local, "net/minecraft/util/math/BlockPos", "func_177958_n"));
        assertEquals(0, calls(local, "net/minecraft/util/math/BlockPos", "func_177956_o"));
        assertEquals(0, calls(local, "net/minecraft/util/math/BlockPos", "func_177952_p"));
        for (String name : new String[]{"func_76670_c", "func_76674_d"}) assertEquals(1,
            calls(local, "net/minecraft/world/chunk/storage/ExtendedBlockStorage", name));
    }
    static ClassNode transformed() throws Exception {
        String path = System.getProperty("ice.phosphor.jar", ""); Assume.assumeFalse("configure ice.phosphor.jar", path.isEmpty());
        byte[] original = read(path, PhosphorLocalPropagationAdapter.TARGET);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(new PhosphorLocalPropagationAdapter().transform("", original, null)).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
    static byte[] read(String path, String owner) throws Exception {
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];
            for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();
        }
    }
    static MethodNode method(ClassNode node, String name) {
        for(MethodNode method:node.methods)if(method.name.equals(name))return method;
        throw new AssertionError(name);
    }
    static int calls(MethodNode method,String owner,String name) {
        int n=0;for(AbstractInsnNode in:method.instructions.toArray())if(in instanceof MethodInsnNode){MethodInsnNode c=(MethodInsnNode)in;if(c.owner.equals(owner)&&c.name.equals(name))n++;}return n;
    }
}
