package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.srp.SrpCachingWalkNodeProcessor;
import dev.rlcraft.ice.optimizer.compat.srp.SrpPathNavigateClimber;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraft.pathfinding.NodeProcessor;
import net.minecraft.pathfinding.PathNavigateClimber;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public class SrpClimbingAndLightAdapterTest {
    @Test
    public void vanillaClimberSubclassOnlyChangesPathfinder() {
        assertEquals(PathNavigateClimber.class, SrpPathNavigateClimber.class.getSuperclass());
        Set<String> methods = new HashSet<String>();
        for (java.lang.reflect.Method method : SrpPathNavigateClimber.class.getDeclaredMethods()) {
            methods.add(method.getName());
        }
        assertEquals(new HashSet<String>(Arrays.asList("getPathFinder", "createProcessor")), methods);
        NodeProcessor processor = SrpPathNavigateClimber.createProcessor();
        assertTrue(processor instanceof SrpCachingWalkNodeProcessor);
        assertTrue(processor.getCanEnterDoors());
    }

    @Test
    public void addsPathfinderWithoutRewritingAnyClimbingBehavior() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SrpClimberNavigatorAdapter.SRP_NAV,
            null, "net/minecraft/pathfinding/PathNavigateGround", null);
        writer.visitEnd();
        byte[] result = new SrpClimberNavigatorAdapter().transform("test", writer.toByteArray(),
            target(SrpClimberNavigatorAdapter.SRP_NAV, "srp-path-node-cache", "srp-climber-navigator"));
        ClassNode node = read(result);
        assertEquals(1, node.methods.size());
        assertEquals(SrpClimberNavigatorAdapter.FINDER_METHOD, node.methods.get(0).name);
        assertEquals(1, calls(node.methods.get(0), SrpClimberNavigatorAdapter.ICE_NAV, "createProcessor"));
    }

    @Test
    public void realJarCoversAllElevenClimbersWithoutChangingFallbackMethods() throws Exception {
        JarFile jar = realJar();
        try {
            byte[] original = bytes(jar, SrpClimberNavigatorAdapter.SRP_NAV);
            ClassNode before = read(original);
            ClassNode after = read(new SrpClimberNavigatorAdapter().transform(before.name, original,
                target(before.name, "srp-path-node-cache", "srp-climber-navigator")));
            assertOriginalMethodsUnchanged(before, after);
            assertNotNull(method(after, SrpClimberNavigatorAdapter.FINDER_METHOD,
                SrpClimberNavigatorAdapter.FINDER_DESC));
            assertNotNull(method(after, "func_75497_a", "(Lnet/minecraft/entity/Entity;D)Z"));
            assertNotNull(method(after, "func_75501_e", "()V"));

            Set<String> srpClimbers = new HashSet<String>();
            Set<String> vanillaClimbers = new HashSet<String>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                byte[] classBytes = bytes(jar, entry.getName().substring(0, entry.getName().length() - 6));
                ClassNode entity = read(classBytes);
                MethodNode factory = method(entity, SrpParasiteNavigatorAdapter.METHOD,
                    SrpParasiteNavigatorAdapter.DESCRIPTOR);
                if (factory == null) continue;
                String simpleName = entity.name.substring(entity.name.lastIndexOf('/') + 1);
                if (allocations(factory, SrpClimberNavigatorAdapter.SRP_NAV) == 1) srpClimbers.add(simpleName);
                if (allocations(factory, SrpClimberNavigatorAdapter.VANILLA_NAV) == 1) {
                    vanillaClimbers.add(simpleName);
                    ClassNode changed = read(new SrpClimberNavigatorAdapter().transform(entity.name, classBytes,
                        target(entity.name, "srp-path-node-cache", "srp-climber-navigator")));
                    MethodNode newFactory = method(changed, factory.name, factory.desc);
                    assertEquals(1, allocations(newFactory, SrpClimberNavigatorAdapter.ICE_NAV));
                    assertEquals(1, calls(newFactory, SrpClimberNavigatorAdapter.ICE_NAV, "<init>"));
                    for (MethodNode prior : entity.methods) {
                        if (prior == factory) continue;
                        assertEquals(trace(prior), trace(method(changed, prior.name, prior.desc)));
                    }
                }
            }
            assertEquals(new HashSet<String>(Arrays.asList("EntityRanracAdapted", "EntityShycoAdapted",
                "EntityShycoFocused", "EntityRanrac", "EntityEsor", "EntityFlog", "EntityGanro",
                "EntityOrch")), srpClimbers);
            assertEquals(new HashSet<String>(Arrays.asList("EntityAta", "EntityMudo", "EntityNuuh")), vanillaClimbers);
        } finally { jar.close(); }
    }

    @Test
    public void realLightSearchPreservesOriginalAndTaskTiming() throws Exception {
        JarFile jar = realJar();
        try {
            byte[] original = bytes(jar, SrpLightSearchAdapter.TARGET);
            ClassNode before = read(original);
            ClassNode after = read(new SrpLightSearchAdapter().transform(before.name, original,
                target(before.name, "srp-light-search", "srp-light-search")));
            assertTrue(after.interfaces.contains(SrpLightSearchAdapter.CALLBACKS));
            for (MethodNode prior : before.methods) {
                String expectedName = "findSource".equals(prior.name) ? SrpLightSearchAdapter.ORIGINAL : prior.name;
                MethodNode next = method(after, expectedName, prior.desc);
                assertNotNull(next);
                assertEquals("original behavior/timing: " + prior.name, trace(prior), trace(next));
            }
            MethodNode wrapper = method(after, "findSource", SrpLightSearchAdapter.FIND_DESC);
            assertEquals(1, calls(wrapper, SrpLightSearchAdapter.BRIDGE, "isEnabled"));
            assertEquals(1, calls(wrapper, SrpLightSearchAdapter.BRIDGE, "findSource"));
            assertEquals(1, calls(wrapper, SrpLightSearchAdapter.TARGET, SrpLightSearchAdapter.ORIGINAL));
            assertFalse(wrapper.instructions.size() == 0);
        } finally { jar.close(); }
    }

    private static void assertOriginalMethodsUnchanged(ClassNode before, ClassNode after) {
        for (MethodNode prior : before.methods) {
            MethodNode next = method(after, prior.name, prior.desc);
            assertNotNull(next);
            assertEquals(prior.name, trace(prior), trace(next));
        }
    }

    private static String trace(MethodNode method) {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        return text.getText().toString();
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.NEW && type.equals(((TypeInsnNode) instruction).desc)) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        return null;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static TargetSpec target(String name, String module, String adapter) {
        return new TargetSpec(name.replace('/', '.'), module, adapter, Collections.<String>emptySet());
    }

    private static JarFile realJar() throws Exception {
        String path = System.getProperty("ice.dregora.srp.jar", System.getProperty("ice.srp.jar", "")).trim();
        Assume.assumeTrue("run with -PsrpJar=<jar>", !path.isEmpty() && new File(path).isFile());
        return new JarFile(path);
    }

    private static byte[] bytes(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally { input.close(); }
    }
}
