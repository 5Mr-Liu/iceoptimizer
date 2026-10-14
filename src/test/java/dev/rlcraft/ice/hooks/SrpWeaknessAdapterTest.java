package dev.rlcraft.ice.hooks;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class SrpWeaknessAdapterTest {
    @Test public void transformsActual1921DamageHandlerAndPreservesOtherCalls() throws Exception {
        String path = System.getProperty("ice.dregora.srp.jar", "");
        Assume.assumeFalse("Provide -PdregoraSrpJar for the actual SRP 1.9.21 fixture", path.isEmpty());
        byte[] original;
        try (JarFile jar = new JarFile(path);
             InputStream input = jar.getInputStream(jar.getJarEntry(SrpWeaknessAdapter.TARGET + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) out.write(buffer, 0, n);
            original = out.toByteArray();
        }
        byte[] transformed = new SrpWeaknessAdapter().transform(SrpWeaknessAdapter.TARGET.replace('/', '.'), original,
            new TargetSpec(SrpWeaknessAdapter.TARGET.replace('/', '.'), "srp-weakness-rules",
                "srp-weakness-rules", Collections.<String>emptySet()));
        MethodNode before = damage(original), after = damage(transformed);
        assertEquals(3, calls(before, "java/lang/String", "split") - calls(after, "java/lang/String", "split"));
        assertEquals(3, calls(before, "java/lang/Float", "parseFloat") - calls(after, "java/lang/Float", "parseFloat"));
        assertEquals(3, calls(after, SrpWeaknessAdapter.BRIDGE, "apply"));
        assertEquals(calls(before, "net/minecraft/entity/monster/EntityMob", "func_70097_a"),
            calls(after, "net/minecraft/entity/monster/EntityMob", "func_70097_a"));
        new Analyzer(new BasicVerifier()).analyze(SrpWeaknessAdapter.TARGET, after);
    }

    private static MethodNode damage(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) if (method.name.equals("func_70097_a")) return method;
        throw new AssertionError("damage method absent");
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }
}
