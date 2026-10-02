package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** LLibrary owns a second private display-list/compiler pair. */
final class AdvancedModelRendererVboAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET =
        "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer";
    static final String COMPILE = "compileDisplayList";
    static final String ORIGINAL = "rlcraftIce$compileDisplayListOriginal";
    static final String DESCRIPTOR = "(F)V";
    static final String GL_STATE_MANAGER =
        "net/minecraft/client/renderer/GlStateManager";
    static final String BRIDGE =
        "dev/rlcraft/ice/optimizer/compat/model/ModelMeshCaptureBridge";

    @Override
    public byte[] transform(String transformedName, byte[] originalClass,
                            TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) {
            throw new IllegalStateException("AdvancedModelRenderer target changed: "
                + node.name);
        }
        MethodNode render = ModelStateBatchMethodProof.method(node, "func_78785_a", "(F)V");
        boolean reviewedRender = ModelSubclassStateBatchAdapter.reviewed(node,
            "func_78785_a", "(F)V",
            "177288483db449649934df4041be6d6a2f2f0e62297016d0ef795a148c7c6c7f");
        MethodNode compile = null;
        int compilers = 0;
        int emitters = 0;
        for (MethodNode method : node.methods) {
            if (COMPILE.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                compile = method;
                compilers++;
            }
            if (ORIGINAL.equals(method.name)) {
                throw new IllegalStateException("AdvancedModelRenderer already adapted");
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                    && GL_STATE_MANAGER.equals(call.owner)
                    && "func_179148_o".equals(call.name)
                    && "(I)V".equals(call.desc)) {
                    call.owner = BRIDGE;
                    call.name = "callList";
                    emitters++;
                }
            }
        }
        if (compilers != 1 || compile == null || emitters != 1
            || (compile.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("AdvancedModelRenderer graph changed: compile="
                + compilers + ", emitters=" + emitters);
        }
        ModelSubclassStateBatchAdapter.wrapCompiler(node, compile, ORIGINAL);
        if (reviewedRender) {
            ModelSubclassStateBatchAdapter.addDisplayListAccess(node, "displayList", "compiled");
            ModelSubclassStateBatchAdapter.wrapRender(node, render);
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}
