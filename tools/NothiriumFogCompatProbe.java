package dev.rlcraft.ice.benchmark.compat;

import java.io.*;
import java.util.zip.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Verifies all supported actual dependency classes; deliberately does not initialize the game. */
public final class NothiriumFogCompatProbe {
    public static void main(String[] args) throws Exception {
        int methods = 0;
        try (ZipFile jar = new ZipFile(args[0])) {
            for (String suffix : new String[]{"renderer/chunk/ChunkRendererGL20", "renderer/chunk/ChunkRendererGL42",
                "renderer/chunk/ChunkRendererGL43", "util/FogUtil"}) {
                String name = "meldexun/nothirium/mc/" + suffix;
                byte[] original;
                try (InputStream in = jar.getInputStream(jar.getEntry(name + ".class"));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                    original = out.toByteArray();
                }
                byte[] changed = new NothiriumFogCompatTransformer().transform(name, name, original);
                ClassNode node = new ClassNode(Opcodes.ASM5);
                new ClassReader(changed).accept(node, ClassReader.EXPAND_FRAMES);
                for (MethodNode method : node.methods) {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                    methods++;
                }
            }
        }
        System.out.println("PASS actual dependency transformed classes=4 verified methods=" + methods);
    }
}
