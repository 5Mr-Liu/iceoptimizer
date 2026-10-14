package dev.rlcraft.ice.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Deduplicates the audited filter object's parameter writes; source binding stays original. */
final class SoundFilterParametersAdapter implements OptimizerBytecodeAdapter {
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/sound/SoundFilterParameters";
    static final String ACCESS = "dev/rlcraft/ice/optimizer/compat/sound/SoundFilterAccess";
    private static final String FIELD = "ice$soundParameters";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec spec) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        if (!("com/tmtravlr/soundfilters/filters/FilterLowPass".equals(node.name)
            || "com/tmtravlr/soundfilters/filters/FilterReverb".equals(node.name)))
            throw new IllegalStateException("Unexpected SoundFilters filter " + node.name);
        node.interfaces.add(ACCESS);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
            FIELD, "Ljava/lang/Object;", null, null));
        int writes = 0, resets = 0;
        for (MethodNode method : node.methods) {
            if ("loadFilter".equals(method.name) && "()V".equals(method.desc)) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("org/lwjgl/openal/EFX10".equals(call.owner) && call.name.startsWith("alGen")) {
                        InsnList reset = new InsnList();
                        reset.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        reset.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "reset", "(Ljava/lang/Object;)V", false));
                        method.instructions.insert(call, reset); resets++;
                    }
                }
            }
            if (!"loadParameters".equals(method.name) || !"()V".equals(method.desc)) continue;
            int enabled = method.maxLocals++;
            InsnList entry = new InsnList();
            entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "begin", "(Ljava/lang/Object;)Z", false));
            entry.add(new VarInsnNode(Opcodes.ISTORE, enabled));
            method.instructions.insert(entry);
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!"org/lwjgl/openal/EFX10".equals(call.owner)) continue;
                if (!("alFilterf".equals(call.name) || "alEffectf".equals(call.name)
                    || "alAuxiliaryEffectSlotf".equals(call.name) || "alEffecti".equals(call.name)
                    || "alAuxiliaryEffectSloti".equals(call.name)))
                    throw new IllegalStateException("Unknown filter parameter call " + call.name);
                InsnList args = new InsnList();
                args.add(new VarInsnNode(Opcodes.ALOAD, 0)); args.add(new VarInsnNode(Opcodes.ILOAD, enabled));
                method.instructions.insertBefore(call, args);
                call.owner = BRIDGE; call.desc = call.desc.replace(")V", "Ljava/lang/Object;Z)V");
                writes++;
            }
        }
        if (writes < 2 || resets < 1) throw new IllegalStateException("Filter parameter graph changed");
        MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, FIELD, "()Ljava/lang/Object;", null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, FIELD, "Ljava/lang/Object;"));
        get.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(get);
        MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, FIELD, "(Ljava/lang/Object;)V", null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, FIELD, "Ljava/lang/Object;"));
        set.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(set);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
        };
        node.accept(writer); return writer.toByteArray();
    }
}
