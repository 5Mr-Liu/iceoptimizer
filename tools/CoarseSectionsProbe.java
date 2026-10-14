package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Verifies the diagnostic excludes hot inner-loop clocks and includes phases. */
public final class CoarseSectionsProbe {
    public static void main(String[] args)throws Exception {
        if(!Boolean.getBoolean("ice.bench.coarseSections"))throw new AssertionError("coarse flag required");
        String arena="dev/rlcraft/ice/optimizer/render/terrain/LwjglTerrainArena";
        ClassNode terrain=transform(arena,Files.readAllBytes(Paths.get("build/classes/java/main/"+arena+".class")));
        expect(terrain,"render",1);expect(terrain,"upload",1);expect(terrain,"endFrame",1);
        for(String name:new String[]{"drawMappedRun","drawLegacyRun","drawMappedRegion","setupArrayPointers","directionStateSafe"})expect(terrain,name,0);
        String models="dev/rlcraft/ice/optimizer/render/entity/LwjglModelMeshCache";
        ClassNode model=transform(models,Files.readAllBytes(Paths.get("build/classes/java/main/"+models+".class")));
        for(MethodNode method:model.methods)for(AbstractInsnNode insn:method.instructions.toArray())
            if(insn instanceof MethodInsnNode&&((MethodInsnNode)insn).owner.contains("RenderSectionClock"))throw new AssertionError("model inner hook retained");
        String fog="com/pg85/otg/forge/events/client/ClientFogHandler";
        try(JarFile jar=new JarFile(args[0]);InputStream in=jar.getInputStream(jar.getJarEntry(fog+".class"));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192];int count;while((count=in.read(block))>=0)out.write(block,0,count);
            ClassNode node=transform(fog,out.toByteArray());expect(node,"onGetFogColor",1);expect(node,"onRenderFog",1);expect(node,"getBiomeConfig",0);
        }
        System.out.println("coarse phase timers verified; terrain/model/biome inner-loop clocks absent; transformed real class bytecode verifies");
    }
    private static ClassNode transform(String owner,byte[] bytes)throws Exception {
        byte[] timed=RenderSectionInstrumenter.transform(owner,bytes);
        if(timed==null)throw new AssertionError("not instrumented: "+owner);
        ClassNode node=new ClassNode(Opcodes.ASM5);new ClassReader(timed).accept(node,ClassReader.EXPAND_FRAMES);
        for(MethodNode method:node.methods)if((method.access&(Opcodes.ACC_NATIVE|Opcodes.ACC_ABSTRACT))==0)
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name,method);
        return node;
    }
    private static void expect(ClassNode node,String name,int expected) {
        int matched=0;
        for(MethodNode method:node.methods)if(method.name.equals(name)) {
            matched++;int count=0;
            for(AbstractInsnNode insn:method.instructions.toArray())if(insn instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)insn;if(call.owner.endsWith("RenderSectionClock")&&call.name.equals("begin"))count++;
            }
            if(count!=expected)throw new AssertionError(name+method.desc+" clocks="+count+" expected="+expected);
        }
        if(matched==0)throw new AssertionError("missing method "+name);
    }
}
