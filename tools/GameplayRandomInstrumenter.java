package dev.rlcraft.ice.benchmark;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Benchmark-only deterministic inputs; does not change damage/AI/scaling formulas. */
final class GameplayRandomInstrumenter {
    static byte[] transform(String name,byte[] bytes) {
        if("c4/champions/common/util/ChampionHelper".equals(name)) return championHelper(bytes);
        if("c4/champions/common/capability/CapabilityChampionship$EventHandler".equals(name)) return championEvents(bytes);
        boolean zombie="net/minecraft/entity/monster/EntityZombie".equals(name);
        boolean health="net/silentchaos512/scalinghealth/event/DifficultyHandler".equals(name);
        boolean kill="com/lycanitesmobs/core/spawner/trigger/KillSpawnTrigger".equals(name);
        if(!zombie&&!health&&!kill)return null;
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
        for(MethodNode method:node.methods) {
            if(zombie&&"()V".equals(method.desc)&&("func_110147_ax".equals(method.name)||"applyEntityAttributes".equals(method.name))) {
                InsnList call=new InsnList();call.add(new VarInsnNode(Opcodes.ALOAD,0));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","beforeAttributes","(Lnet/minecraft/entity/EntityLivingBase;)V",false));
                method.instructions.insert(call);edits++;
            }
            if(health&&"increaseEntityHealth".equals(method.name)&&"(Lnet/minecraft/entity/EntityLivingBase;)Z".equals(method.desc)) {
                for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof FieldInsnNode) {
                    FieldInsnNode field=(FieldInsnNode)instruction;
                    if(field.getOpcode()==Opcodes.GETSTATIC&&"net/silentchaos512/scalinghealth/ScalingHealth".equals(field.owner)
                        &&"random".equals(field.name)&&"Ljava/util/Random;".equals(field.desc)) {
                        InsnList call=new InsnList();call.add(new VarInsnNode(Opcodes.ALOAD,1));
                        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","healthRandom","(Ljava/util/Random;Lnet/minecraft/entity/EntityLivingBase;)Ljava/util/Random;",false));
                        method.instructions.insert(field,call);edits++;
                    }
                }
            }
            if(kill&&"onKill".equals(method.name)&&"(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/entity/EntityLiving;)V".equals(method.desc)) {
                for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    if(call.getOpcode()==Opcodes.INVOKEVIRTUAL&&"net/minecraft/entity/player/EntityPlayer".equals(call.owner)
                        &&("func_70681_au".equals(call.name)||"getRNG".equals(call.name))&&"()Ljava/util/Random;".equals(call.desc)) {
                        InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,2));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","killRandom","(Ljava/util/Random;Lnet/minecraft/entity/EntityLivingBase;)Ljava/util/Random;",false));
                        method.instructions.insert(call,hook);edits++;
                    }
                }
            }
        }
        if(edits!=1)throw new IllegalStateException("Expected one random input hook: "+name+" found "+edits);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        System.setProperty("ice.bench.randomHook."+(zombie?"zombie":health?"health":"kill"),"true");
        System.out.println("ICE_BENCH deterministic_random_hook="+name);
        return writer.toByteArray();
    }
    private static byte[] championHelper(byte[] bytes) {
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
        for(MethodNode method:node.methods) {
            if(!"generateRank".equals(method.name)&&!"generateAffixes".equals(method.name)&&!"generateRandomName".equals(method.name))continue;
            for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof FieldInsnNode) {
                FieldInsnNode field=(FieldInsnNode)instruction;
                if(field.getOpcode()==Opcodes.GETSTATIC&&node.name.equals(field.owner)&&"rand".equals(field.name)&&"Ljava/util/Random;".equals(field.desc)) {
                    method.instructions.insert(field,new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","championRandom","(Ljava/util/Random;)Ljava/util/Random;",false));
                    edits++;
                }
            }
        }
        if(edits!=6)throw new IllegalStateException("Expected six champion RNG inputs, found "+edits);
        return finish(node,"championHelper");
    }
    private static byte[] championEvents(byte[] bytes) {
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int edits=0;
        for(MethodNode original:new java.util.ArrayList<MethodNode>(node.methods)) {
            boolean join="entityJoin".equals(original.name)&&"(Lnet/minecraftforge/event/entity/EntityJoinWorldEvent;)V".equals(original.desc);
            boolean spawn="entitySpawn".equals(original.name)&&"(Lnet/minecraftforge/event/entity/living/LivingSpawnEvent$SpecialSpawn;)V".equals(original.desc);
            if(!join&&!spawn)continue;
            if((original.access&Opcodes.ACC_STATIC)==0)throw new IllegalStateException("Champion event is not static");
            String event=join?"net/minecraftforge/event/entity/EntityJoinWorldEvent":"net/minecraftforge/event/entity/living/LivingSpawnEvent$SpecialSpawn";
            MethodNode wrapper=new MethodNode(original.access,original.name,original.desc,original.signature,null);
            wrapper.visibleAnnotations=original.visibleAnnotations;wrapper.invisibleAnnotations=original.invisibleAnnotations;
            original.visibleAnnotations=null;original.invisibleAnnotations=null;
            original.name="ice$original$"+original.name;original.access|=Opcodes.ACC_SYNTHETIC;
            Label start=new Label(),end=new Label(),handler=new Label();
            wrapper.visitCode();wrapper.visitVarInsn(Opcodes.ALOAD,0);
            wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL,event,"getEntity","()Lnet/minecraft/entity/Entity;",false);
            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","beginChampion","(Lnet/minecraft/entity/Entity;)Ljava/util/Random;",false);
            wrapper.visitVarInsn(Opcodes.ASTORE,1);wrapper.visitLabel(start);
            wrapper.visitVarInsn(Opcodes.ALOAD,0);wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,node.name,original.name,original.desc,false);
            wrapper.visitLabel(end);wrapper.visitVarInsn(Opcodes.ALOAD,1);
            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","endChampion","(Ljava/util/Random;)V",false);
            wrapper.visitInsn(Opcodes.RETURN);wrapper.visitLabel(handler);
            wrapper.visitFrame(Opcodes.F_FULL,2,new Object[]{event,"java/util/Random"},1,new Object[]{"java/lang/Throwable"});
            wrapper.visitVarInsn(Opcodes.ALOAD,1);
            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/LiveCombatRandom","endChampion","(Ljava/util/Random;)V",false);
            wrapper.visitInsn(Opcodes.ATHROW);
            wrapper.visitTryCatchBlock(start,end,handler,null);wrapper.visitMaxs(0,0);wrapper.visitEnd();
            node.methods.add(wrapper);edits++;
        }
        if(edits!=2)throw new IllegalStateException("Expected two champion event scopes, found "+edits);
        return finish(node,"championEvents");
    }
    private static byte[] finish(ClassNode node,String hook) {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        System.setProperty("ice.bench.randomHook."+hook,"true");
        System.out.println("ICE_BENCH deterministic_random_hook="+node.name);
        return writer.toByteArray();
    }
}
