package dev.rlcraft.ice.hooks;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/** One-time proof of the common-model branch graph, excluding unrelated HEAD/TAIL hooks. */
final class CommonModelStateBatchProof {
    private static final String BIPED_FORGE =
        "d07d4059105bfc9ab3d13235d2c98925002327124bcaa32c15921e161d545a28";
    private static final String BIPED_G5 =
        "f5af07c96de6e1cabf645b194283b550ee0bd6fef23a65e943db226cba29cb2b";
    private static final String QUADRUPED_FORGE =
        "53c30424bcc1e3941a464223fb9da3ad1e09888758a5ff1952dd6831a5028036";
    private static final String QUADRUPED_G5 =
        "886c9052093e98e1cc863a3f22431ba28a5ae389be625e1fe44d2b6a8edc49f9";

    final AbstractInsnNode first;
    final AbstractInsnNode endExclusive;

    private CommonModelStateBatchProof(AbstractInsnNode first, AbstractInsnNode endExclusive) {
        this.first = first;
        this.endExclusive = endExclusive;
    }

    static CommonModelStateBatchProof review(MethodNode render, String owner, boolean biped) {
        CommonModelStateBatchProof region = region(render, owner, biped);
        if (region == null || !region.closedGraph(render)) return null;
        String digest = ModelStateBatchMethodProof.digestRegion(render, region.first, region.endExclusive);
        return (biped ? BIPED_FORGE.equals(digest) || BIPED_G5.equals(digest)
            : QUADRUPED_FORGE.equals(digest) || QUADRUPED_G5.equals(digest)) ? region : null;
    }

    private static CommonModelStateBatchProof region(MethodNode render, String owner, boolean biped) {
        MethodInsnNode animation = null;
        MethodInsnNode lastPart = null;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && "func_78087_a".equals(call.name)
                && "(FFFFFFLnet/minecraft/entity/Entity;)V".equals(call.desc)) {
                if (animation != null) return null;
                animation = call;
            }
            if (ModelRendererVboAdapter.TARGET.equals(call.owner)
                && "func_78785_a".equals(call.name) && "(F)V".equals(call.desc)) lastPart = call;
        }
        if (animation == null || lastPart == null
            || render.instructions.indexOf(animation) >= render.instructions.indexOf(lastPart)) return null;
        AbstractInsnNode boundary = nextInstruction(lastPart);
        if (biped) {
            if (!(boundary instanceof MethodInsnNode)) return null;
            MethodInsnNode pop = (MethodInsnNode) boundary;
            if (!ModelRendererVboAdapter.GL_STATE_MANAGER.equals(pop.owner)
                || !"func_179121_F".equals(pop.name) || !"()V".equals(pop.desc)) return null;
            boundary = nextInstruction(pop);
        }
        if (boundary == null) return null;
        return new CommonModelStateBatchProof(animation.getNext(), boundary);
    }

    private boolean closedGraph(MethodNode method) {
        int start = method.instructions.indexOf(first);
        int end = method.instructions.indexOf(endExclusive);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) return false;
            if (!(instruction instanceof JumpInsnNode)) continue;
            int from = method.instructions.indexOf(instruction);
            int to = method.instructions.indexOf(((JumpInsnNode) instruction).label);
            boolean inside = from >= start && from < end;
            if (inside && (to < start || to >= end)) return false;
            // Prefix cancellation is allowed, but nobody may enter the middle of a held batch.
            if (!inside && to >= start && to < end) return false;
        }
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            int protectedStart = method.instructions.indexOf(handler.start);
            int protectedEnd = method.instructions.indexOf(handler.end);
            int destination = method.instructions.indexOf(handler.handler);
            if (protectedStart < end && protectedEnd > start
                || destination >= start && destination < end) return false;
        }
        return start >= 0 && start < end;
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }
}
