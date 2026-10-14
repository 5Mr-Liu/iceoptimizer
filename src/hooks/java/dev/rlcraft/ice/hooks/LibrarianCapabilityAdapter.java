package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Keeps live provider lookup and field/facing rules, eliminating temporary names. */
final class LibrarianCapabilityAdapter implements OptimizerBytecodeAdapter {
    static final String TARGET = "com/teamwizardry/librarianlib/features/saving/FieldCache";
    static final String BRIDGE = "dev/rlcraft/ice/optimizer/compat/events/LibrarianCapabilityBridge";
    @Override public byte[] transform(String name, byte[] bytes, TargetSpec target) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode method = ServerModAdapterSupport.require(node, "hasCapability",
            "(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/util/EnumFacing;)Z");
        MethodNode fast = ServerModAdapterSupport.copy(method, "ice$fast$hasCapability");
        MethodInsnNode interfaces = null, providers = null;
        FieldInsnNode companion = null;
        FieldInsnNode secondMeta = null;
        int metas = 0;
        for (AbstractInsnNode instruction : fast.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("getInterfaces".equals(call.name) && "()[Ljava/lang/Class;".equals(call.desc)) interfaces = call;
                if ("getProviders".equals(call.name) && "()Ljava/util/IdentityHashMap;".equals(call.desc)) providers = call;
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (TARGET.equals(field.owner) && "Companion".equals(field.name)) companion = field;
                if (TARGET.equals(field.owner) && "meta".equals(field.name) && ++metas == 2) secondMeta = field;
            }
        }
        if (interfaces == null || providers == null || companion == null || secondMeta == null) {
            throw new IllegalStateException("LibrarianLib interface capability graph changed");
        }
        AbstractInsnNode remaining = secondMeta.getPrevious();
        while (remaining.getOpcode() < 0) remaining = remaining.getPrevious();
        if (!(remaining instanceof VarInsnNode) || remaining.getOpcode() != Opcodes.ALOAD
            || ((VarInsnNode) remaining).var != 0) throw new IllegalStateException("Field metadata receiver changed");
        InsnList replacement = new InsnList();
        replacement.add(new FieldInsnNode(companion.getOpcode(), companion.owner, companion.name, companion.desc));
        replacement.add(new MethodInsnNode(providers.getOpcode(), providers.owner, providers.name, providers.desc, providers.itf));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "matches",
            "([Ljava/lang/Class;Ljava/util/Map;Ljava/lang/Object;)Z", false));
        LabelNode matched = new LabelNode();
        replacement.add(new JumpInsnNode(Opcodes.IFNE, matched));
        replacement.add(new InsnNode(Opcodes.ICONST_0));
        replacement.add(new InsnNode(Opcodes.IRETURN));
        replacement.add(matched);
        AbstractInsnNode at = interfaces.getNext();
        while (at != remaining) {
            AbstractInsnNode next = at.getNext();
            fast.instructions.remove(at);
            at = next;
        }
        fast.instructions.insert(interfaces, replacement);
        fast.localVariables = null;
        ServerModAdapterSupport.route(node, method, fast, BRIDGE, "isEnabled", false);
        return ServerModAdapterSupport.write(reader, node);
    }
}
