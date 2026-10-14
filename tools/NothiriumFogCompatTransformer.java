package dev.rlcraft.ice.benchmark.compat;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Narrow, independently authored ABI adaptation; no dependency code is bundled. */
public final class NothiriumFogCompatTransformer implements IClassTransformer {
    private static final String SHADER = "meldexun/renderlib/util/GLShader";
    private static final String FOG = "meldexun/nothirium/mc/util/FogUtil";
    private static final String BRIDGE = "dev/rlcraft/ice/benchmark/compat/NothiriumFogCompat";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || transformedName == null) return bytes;
        String target = transformedName.replace('.', '/');
        boolean renderer = target.equals("meldexun/nothirium/mc/renderer/chunk/ChunkRendererGL43")
            || target.equals("meldexun/nothirium/mc/renderer/chunk/ChunkRendererGL42")
            || target.equals("meldexun/nothirium/mc/renderer/chunk/ChunkRendererGL20");
        if (!renderer && !target.equals(FOG)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int builds = 0, fogs = 0, distance = 0;
        for (MethodNode method : node.methods) {
            if (renderer) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (method.name.equals("<init>") && call.owner.equals(SHADER + "$Builder")
                    && call.name.equals("build") && call.desc.equals("(Ljava/util/function/Consumer;)L" + SHADER + ";")) {
                    call.owner = BRIDGE;
                    call.desc = "(L" + SHADER + "$Builder;Ljava/util/function/Consumer;)L" + SHADER + ";";
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    builds++;
                }
                if (call.owner.equals(FOG) && call.name.equals("setupFogFromGL")
                    && call.desc.equals("(L" + SHADER + ";)V")) {
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
                    method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        BRIDGE, "setup", call.desc, false));
                    fogs++;
                }
            }
            if (!renderer && method.name.equals("calculateFogEndSqr") && method.desc.equals("()D")) {
                // Horizontal distance rejection is not conservative for eye-plane fog.
                // Retain the original frustum/section visibility path at the configured view distance.
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new LdcInsnNode(Double.MAX_VALUE));
                method.instructions.add(new InsnNode(Opcodes.DRETURN));
                distance++;
            }
        }
        if (renderer ? builds != 1 || fogs != 1 : distance != 1)
            throw new IllegalStateException("Unsupported fog ABI: " + target + "=" + builds + "/" + fogs + "/" + distance);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        System.out.println("ICE_FOG_COMPAT transformed " + target);
        return writer.toByteArray();
    }
}
