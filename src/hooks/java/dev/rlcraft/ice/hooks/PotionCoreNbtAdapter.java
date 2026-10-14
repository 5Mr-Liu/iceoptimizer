package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Event-local numeric reads; no entity state survives the original event invocation. */
final class PotionCoreNbtAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/tmtravlr/potioncore/PotionCoreEventHandler";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/nbt/PotionCoreNbtBridge";
    static final String ORIGINAL = "ice$originalPotionCoreLivingUpdate";
    @Override public byte[] transform(String name, byte[] original, TargetSpec target) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!TARGET.equals(node.name)) throw new IllegalStateException("PotionCore target changed");
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            if (ORIGINAL.equals(candidate.name)) throw new IllegalStateException("PotionCore adapter already installed");
            if ("onLivingUpdate".equals(candidate.name)
                && "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V".equals(candidate.desc)) method = candidate;
        }
        if (method == null) throw new IllegalStateException("PotionCore LivingUpdate missing");
        MethodNode preserved = new MethodNode(Opcodes.ASM5, method.access, method.name, method.desc,
            method.signature, method.exceptions.toArray(new String[method.exceptions.size()]));
        method.accept(preserved);
        preserved.name = ORIGINAL;
        preserved.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        preserved.visibleAnnotations = null;
        preserved.invisibleAnnotations = null;
        node.methods.add(preserved);
        int changed = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!"net/minecraft/nbt/NBTTagCompound".equals(call.owner)) continue;
            String replacement = "func_74762_e".equals(call.name) ? "getInteger"
                : "func_74767_n".equals(call.name) ? "getBoolean"
                : "func_74769_h".equals(call.name) ? "getDouble" : null;
            if (replacement != null) {
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                    replacement, "(Lnet/minecraft/nbt/NBTTagCompound;" + call.desc.substring(1), false));
                changed++;
            }
        }
        if (changed < 10) throw new IllegalStateException("PotionCore numeric read graph changed: " + changed);
        LabelNode optimized = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isEnabled", "()Z", false));
        entry.add(new JumpInsnNode(Opcodes.IFNE, optimized));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, ORIGINAL, method.desc, false));
        entry.add(new InsnNode(Opcodes.RETURN));
        entry.add(optimized);
        method.instructions.insert(entry);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String left, String right) { return "java/lang/Object"; }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
}
