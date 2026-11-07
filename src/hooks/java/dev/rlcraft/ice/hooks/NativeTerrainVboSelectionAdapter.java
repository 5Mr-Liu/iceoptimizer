package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Context selection and VBO ownership confined to ICE terrain entry points. */
final class NativeTerrainVboSelectionAdapter implements OptimizerBytecodeAdapter {
    static final String HELPER = "net/minecraft/client/renderer/OpenGlHelper";
    static final String TERRAIN_VBO = "ice$terrainUseVbo";
    static final String STARTUP = "dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainStartup";
    private static final String GLOBAL = "net/minecraft/client/renderer/RenderGlobal";
    private static final String CHUNK = "net/minecraft/client/renderer/chunk/RenderChunk";
    private static final String DISPATCHER = "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher";

    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5); reader.accept(node, 0);
        if (node.name.equals(HELPER)) helper(node);
        else terrain(node);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }

    private static void helper(ClassNode node) {
        MethodNode selection = null, initialize = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(TERRAIN_VBO)) throw new IllegalStateException("Duplicate terrain VBO helper");
            if (method.name.equals("func_176075_f") || method.name.equals("useVbo")) selection = method;
            if (method.name.equals("func_77474_a") || method.name.equals("initializeTextures")) initialize = method;
        }
        if (selection == null || initialize == null || !selection.desc.equals("()Z") || !initialize.desc.equals("()V")
            || (selection.access & Opcodes.ACC_STATIC) == 0 || (initialize.access & Opcodes.ACC_STATIC) == 0)
            throw new IllegalStateException("Missing OpenGlHelper initialization or VBO selection");
        // Leave global useVbo bytecode untouched. Its clone retains every
        // hardware/OptiFine guard and ignores only the saved vanilla VBO option.
        MethodNode terrain = NativeTerrainPauseAdapter.copy(selection);
        terrain.name = TERRAIN_VBO;
        terrain.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        int options = 0;
        for (AbstractInsnNode insn : terrain.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals("Config") && call.name.equals("isMultiTexture") && call.desc.equals("()Z"))
                    terrain.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainMaterials",
                        "rejectUnsupportedMultiTexture", "(Z)Z", false));
            }
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (field.owner.equals("net/minecraft/client/settings/GameSettings")
                    && (field.name.equals("field_178881_t") || field.name.equals("useVbo"))
                    && field.desc.equals("Z") && field.getOpcode() == Opcodes.GETFIELD) {
                    InsnList enabled = new InsnList();
                    enabled.add(new InsnNode(Opcodes.POP)); enabled.add(new InsnNode(Opcodes.ICONST_1));
                    terrain.instructions.insertBefore(field, enabled); terrain.instructions.remove(field); options++;
                }
            }
            if (insn.getOpcode() == Opcodes.IRETURN)
                terrain.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    STARTUP, "requireTerrainVbo", "(Z)Z", false));
        }
        if (options != 1) throw new IllegalStateException("Unexpected vanilla VBO option count: " + options);
        int returns = 0;
        for (AbstractInsnNode insn : initialize.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
            initialize.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                STARTUP, "initialize", "()V", false)); returns++;
        }
        if (returns == 0) throw new IllegalStateException("Missing context initialization exit");
        node.methods.add(terrain);
    }

    private static void terrain(ClassNode node) {
        if (!node.name.equals(GLOBAL) && !node.name.equals(CHUNK) && !node.name.equals(DISPATCHER))
            throw new IllegalStateException("Unexpected terrain VBO target: " + node.name);
        int changed = 0;
        for (MethodNode method : node.methods) {
            String name = method.name;
            if (name.startsWith(NativeTerrainPauseAdapter.PREFIX)) name = name.substring(NativeTerrainPauseAdapter.PREFIX.length());
            boolean containers = node.name.equals(GLOBAL) && (name.equals("<init>") || name.equals("func_72712_a"));
            boolean calls = node.name.equals(CHUNK) && name.equals("<init>")
                || node.name.equals(DISPATCHER) && name.equals("func_188245_a")
                || node.name.equals(GLOBAL) && name.equals("func_174982_a");
            if (!containers && !calls) continue;
            int replacements = 0;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (calls && call.owner.equals(HELPER) && call.name.equals("func_176075_f") && call.desc.equals("()Z")) {
                        call.name = TERRAIN_VBO; replacements++;
                    }
                    if (containers && call.name.equals("<init>")) call.owner = vboClass(call.owner);
                } else if (containers && insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW) {
                    TypeInsnNode type = (TypeInsnNode) insn;
                    String replacement = vboClass(type.desc);
                    if (!replacement.equals(type.desc)) { type.desc = replacement; replacements++; }
                }
            }
            if (containers && replacements != 0) {
                InsnList guard = new InsnList();
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, TERRAIN_VBO, "()Z", false));
                guard.add(new InsnNode(Opcodes.POP));
                if (name.equals("<init>")) {
                    AbstractInsnNode superCall = null;
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        if (call.owner.equals(node.superName) && call.name.equals("<init>")) { superCall = insn; break; }
                    }
                    if (superCall == null) throw new IllegalStateException("Missing RenderGlobal super constructor");
                    method.instructions.insert(superCall, guard);
                } else method.instructions.insert(guard);
            }
            changed += replacements;
        }
        if (changed == 0) throw new IllegalStateException("No terrain VBO entry points found in " + node.name);
    }

    private static String vboClass(String owner) {
        if (owner.equals("net/minecraft/client/renderer/RenderList")) return "net/minecraft/client/renderer/VboRenderList";
        if (owner.equals("net/minecraft/client/renderer/chunk/ListChunkFactory")) return "net/minecraft/client/renderer/chunk/VboChunkFactory";
        return owner;
    }
}
