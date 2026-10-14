package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class CommonModelStateBatchAdapterTest {
    @Test public void targetsAndAdapterAreRegisteredForBothCommonModels() {
        assertNotNull(OptimizerAdapterRegistry.find("modern-common-model-state-batch"));
        assertEquals("modern-common-model-state-batch", OptimizerTargetCatalog.find(
            CommonModelStateBatchAdapter.BIPED.replace('/', '.')).adapterId);
        assertEquals("modern-common-model-state-batch", OptimizerTargetCatalog.find(
            CommonModelStateBatchAdapter.QUADRUPED.replace('/', '.')).adapterId);
    }

    @Test public void realForgeCommonModelsPreserveCallbacksAndVerifyAllScopeBranches() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar", !path.isEmpty() && new File(path).isFile());
        try (JarFile jar = new JarFile(path)) {
            check(read(jar, CommonModelStateBatchAdapter.BIPED), CommonModelStateBatchAdapter.BIPED, 14);
            check(read(jar, CommonModelStateBatchAdapter.QUADRUPED), CommonModelStateBatchAdapter.QUADRUPED, 12);
        }
    }

    @Test public void realOptifineG5CommonModelsPreserveCallbacksAndVerifyAllScopeBranches() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            check(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')),
                CommonModelStateBatchAdapter.BIPED, 14);
            check(support.patchAndRemap("bqm", CommonModelStateBatchAdapter.QUADRUPED.replace('/', '.')),
                CommonModelStateBatchAdapter.QUADRUPED, 12);
        }
    }

    @Test public void extraRenderCallbackDeclinesCommonScope() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = node(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
            MethodNode render = render(node);
            InsnList callback = callback();
            render.instructions.insert(firstPart(render), callback);
            assertSkipped(node);
        }
    }

    @Test public void headAndTailCallbacksRemainOutsideHeldScope() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = node(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
            MethodNode render = render(node);
            render.instructions.insert(callback());
            AbstractInsnNode last = render.instructions.getLast();
            while (last.getOpcode() != Opcodes.RETURN) last = last.getPrevious();
            render.instructions.insertBefore(last, callback());
            check(bytes(node), node.name, 14);
        }
    }

    @Test public void clientStateChangeInsidePartsDeclinesBatch() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = node(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
            InsnList state = new InsnList();
            state.add(new InsnNode(Opcodes.ICONST_0));
            state.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                ModelRendererVboAdapter.GL_STATE_MANAGER, "func_179144_i", "(I)V", false));
            render(node).instructions.insert(firstPart(render(node)), state);
            assertSkipped(node);
        }
    }

    @Test public void prefixJumpIntoPartsDeclinesBatchEvenWhenRegionDigestMatches() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = node(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
            MethodNode render = render(node);
            LabelNode interior = new LabelNode();
            render.instructions.insert(firstPart(render), interior);
            render.instructions.insert(new JumpInsnNode(Opcodes.GOTO, interior));
            assertSkipped(node);
        }
    }

    @Test public void externalExceptionHandlerOverPartsDeclinesBatch() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            ClassNode node = node(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
            MethodNode render = render(node);
            LabelNode start = new LabelNode();
            LabelNode end = new LabelNode();
            LabelNode handler = new LabelNode();
            render.instructions.insert(start);
            render.instructions.add(end);
            render.instructions.add(handler);
            render.instructions.add(new InsnNode(Opcodes.ATHROW));
            render.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
            assertSkipped(node);
        }
    }

    @Test public void actualDregoraLlibraryPatchedBipedTailRemainsOutsideBatch() throws Exception {
        String directory = System.getProperty("ice.dregora.modsDir", "").trim();
        Assume.assumeTrue("run with -PdregoraModsDir", !directory.isEmpty());
        File dump = new File(new File(directory).getParentFile(),
            "llibrary/debug/net.minecraft.client.model.ModelBiped.class");
        Assume.assumeTrue("requires the existing LLibrary game transformation dump", dump.isFile());
        byte[] original = java.nio.file.Files.readAllBytes(dump.toPath());
        MethodNode originalRender = render(node(original));
        assertEquals(1, calls(originalRender,
            "net/ilexiconn/llibrary/server/core/patcher/LLibraryHooks", "renderModel"));
        check(original, CommonModelStateBatchAdapter.BIPED, 14);
    }

    private static InsnList callback() {
        InsnList callback = new InsnList();
        callback.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/System", "nanoTime", "()J", false));
        callback.add(new InsnNode(Opcodes.POP2));
        return callback;
    }

    private static AbstractInsnNode firstPart(MethodNode render) {
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode
                && ModelRendererVboAdapter.TARGET.equals(((MethodInsnNode) instruction).owner)
                && "func_78785_a".equals(((MethodInsnNode) instruction).name)) return instruction;
        }
        throw new AssertionError("missing model part draw");
    }

    private static byte[] bytes(ClassNode node) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void assertSkipped(ClassNode node) {
        try {
            new CommonModelStateBatchAdapter().transform(node.name, bytes(node), target(node.name));
            fail("the changed batch graph must be declined");
        } catch (OptimizerAdapterSkippedException expected) { }
    }

    private static void check(byte[] original, String owner, int partCalls) throws Exception {
        byte[] adapted = new CommonModelStateBatchAdapter().transform(owner, original, target(owner));
        ClassNode classNode = node(adapted);
        MethodNode rendered = render(classNode);
        assertEquals(2, calls(rendered, CommonModelStateBatchAdapter.BRIDGE, "beginRoots"));
        assertEquals(partCalls - 2, calls(rendered, CommonModelStateBatchAdapter.BRIDGE, "beforeRoot"));
        assertEquals(1, calls(rendered, CommonModelStateBatchAdapter.BRIDGE, "end"));
        assertEquals(1, calls(rendered, CommonModelStateBatchAdapter.BRIDGE, "endAfterFailure"));
        assertEquals(partCalls, calls(rendered, ModelRendererVboAdapter.TARGET, "func_78785_a"));
        assertEquals(1, calls(rendered, owner, "func_78087_a"));
        assertEquals(calls(render(node(original)), "net/minecraft/entity/Entity", "func_70093_af"),
            calls(rendered, "net/minecraft/entity/Entity", "func_70093_af"));
        assertNoCallbacksInsideHeldScope(rendered);
        for (MethodNode method : classNode.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(classNode.name, method);
        }
    }

    /** Follow both real child/adult branches, rather than assuming source instruction order. */
    private static void assertNoCallbacksInsideHeldScope(MethodNode method) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        int scopeLocal = -1;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof MethodInsnNode
                && CommonModelStateBatchAdapter.BRIDGE.equals(((MethodInsnNode) instruction).owner)
                && "beginRoots".equals(((MethodInsnNode) instruction).name)) {
                scopeLocal = ((VarInsnNode) instruction.getNext()).var;
                break;
            }
        }
        ArrayDeque<Integer> pending = new ArrayDeque<Integer>();
        Set<Integer> seen = new HashSet<Integer>();
        pending.add(Integer.valueOf(0));
        while (!pending.isEmpty()) {
            int key = pending.removeFirst().intValue();
            if (!seen.add(Integer.valueOf(key))) continue;
            int index = key >>> 1;
            boolean active = (key & 1) != 0;
            AbstractInsnNode instruction = instructions[index];
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (CommonModelStateBatchAdapter.BRIDGE.equals(call.owner)) {
                    if ("beginRoots".equals(call.name)) {
                        pending.add(Integer.valueOf((index + 1) << 1));
                        active = true;
                    }
                    else if ("end".equals(call.name) || "endAfterFailure".equals(call.name)) active = false;
                } else if (active) {
                    boolean matrix = ModelRendererVboAdapter.GL_STATE_MANAGER.equals(call.owner)
                        && ("func_179094_E".equals(call.name) || "func_179121_F".equals(call.name)
                            || "func_179152_a".equals(call.name) || "func_179109_b".equals(call.name));
                    boolean part = ModelRendererVboAdapter.TARGET.equals(call.owner)
                        && "func_78785_a".equals(call.name);
                    if (!matrix && !part) fail("callback inside held client scope: " + call.owner + '.' + call.name);
                }
            }
            if (instruction.getOpcode() == Opcodes.RETURN || instruction.getOpcode() == Opcodes.ATHROW) continue;
            if (instruction instanceof JumpInsnNode) {
                int destination = method.instructions.indexOf(((JumpInsnNode) instruction).label);
                AbstractInsnNode previous = instruction.getPrevious();
                while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                if (instruction.getOpcode() == Opcodes.IFEQ && previous instanceof VarInsnNode
                    && previous.getOpcode() == Opcodes.ILOAD && ((VarInsnNode) previous).var == scopeLocal) {
                    pending.add(Integer.valueOf(((active ? index + 1 : destination) << 1)
                        | (active ? 1 : 0)));
                    continue;
                }
                pending.add(Integer.valueOf((destination << 1) | (active ? 1 : 0)));
                if (instruction.getOpcode() == Opcodes.GOTO) continue;
            }
            if (index + 1 < instructions.length) {
                pending.add(Integer.valueOf(((index + 1) << 1) | (active ? 1 : 0)));
            }
        }
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

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode render(ClassNode owner) {
        return ModelStateBatchMethodProof.method(owner, CommonModelStateBatchAdapter.RENDER,
            CommonModelStateBatchAdapter.RENDER_DESCRIPTOR);
    }

    private static TargetSpec target(String owner) {
        return new TargetSpec(owner, "modern-entity-backend,modern-tesr-backend",
            "modern-common-model-state-batch", Collections.<String>emptySet());
    }

    private static byte[] read(JarFile jar, String owner) throws Exception {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
