package dev.rlcraft.ice.hooks;

import com.pg85.otg.configuration.biome.BiomeConfig;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the actual jar getter and a single-lookup rewrite in isolated
 * classes. No world/config caching, no production transformer modification. */
public final class OtgBiomeLookupProbe {
    public abstract static class Lookup {
        public final HashMap<String,BiomeConfig[]> otgBiomeIdsByWorld=new HashMap<>();
        public abstract BiomeConfig[] lookup(String world);
    }
    private static final String ENGINE="com/pg85/otg/OTGEngine";
    private static final String BASE=Type.getInternalName(Lookup.class);
    private static final String ARRAY="[Lcom/pg85/otg/configuration/biome/BiomeConfig;";
    private static volatile long sink;
    public static void main(String[] args)throws Exception {
        byte[] original;
        try(JarFile jar=new JarFile(args[0]);InputStream in=jar.getInputStream(jar.getJarEntry(ENGINE+".class"));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192];int n;while((n=in.read(block))>=0)out.write(block,0,n);original=out.toByteArray();
        }
        Lookup[] lookups={create(original,false),create(original,true)};
        String[] keys={"overworld","Aa","BB","AaAa","BBBB",null,"missing"};
        Random random=new Random(0x12929L);
        for(int round=0;round<4096;round++) {
            String key=keys[random.nextInt(keys.length)];int op=random.nextInt(3);
            BiomeConfig[] value=op==0?null:new BiomeConfig[random.nextInt(8)+1];
            for(Lookup lookup:lookups) {
                if(op==2)lookup.otgBiomeIdsByWorld.remove(key);else lookup.otgBiomeIdsByWorld.put(key,value);
            }
            String query=keys[random.nextInt(keys.length)];
            BiomeConfig[] a=lookups[0].lookup(query),b=lookups[1].lookup(query);
            if(lookups[0].otgBiomeIdsByWorld.containsKey(query)) {
                if(a!=b)throw new AssertionError("mapped array identity/null changed");
            } else {
                if(a==b||a.length!=2048||b.length!=2048||!Arrays.equals(a,b)||b==lookups[1].lookup(query))
                    throw new AssertionError("missing world must return distinct empty2048 arrays");
            }
        }
        System.out.println("oracle_cases=4096 null_keys_null_values_collisions_removal_replacement_and_fresh_missing_arrays=passed");
        for(Lookup lookup:lookups) {
            lookup.otgBiomeIdsByWorld.clear();
            for(String key:keys)lookup.otgBiomeIdsByWorld.put(key,new BiomeConfig[2048]);
        }
        for(int i=0;i<40;i++)for(Lookup lookup:lookups)run(lookup,keys,200000);
        double[][] times=new double[2][51];
        for(int round=0;round<51;round++)for(int step=0;step<2;step++) {
            int mode=(round+step)%2;long begin=System.nanoTime();run(lookups[mode],keys,500000);
            times[mode][round]=(System.nanoTime()-begin)/500000.0;
        }
        for(int mode=0;mode<2;mode++) {
            Arrays.sort(times[mode]);System.out.printf(Locale.ROOT,"getter=%s hit_ns_p50=%.3f hit_ns_p95=%.3f%n",mode==0?"jar_original":"single_lookup",times[mode][25],times[mode][48]);
        }
        System.out.println("tools_only=true fog_pixels_and_whole_frame_gain=unproven sink="+sink);
    }
    private static void run(Lookup lookup,String[] keys,int count) {
        long result=0;int index=0;
        for(int i=0;i<count;i++) {result+=lookup.lookup(keys[index]).length;if(++index==keys.length)index=0;}
        sink=result;
    }
    private static Lookup create(byte[] original,boolean candidate)throws Exception {
        ClassNode source=new ClassNode(Opcodes.ASM5);new ClassReader(original).accept(source,ClassReader.EXPAND_FRAMES);
        MethodNode getter=null;
        for(MethodNode method:source.methods)if(method.name.equals("getOTGBiomeIds")&&method.desc.equals("(Ljava/lang/String;)"+ARRAY))getter=method;
        if(getter==null)throw new AssertionError("jar getter missing");
        String name="dev/rlcraft/ice/hooks/ProbeOtgLookup"+(candidate?"Single":"Original");
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name,null,BASE,null);
        MethodVisitor init=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.visitCode();init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,BASE,"<init>","()V",false);
        init.visitInsn(Opcodes.RETURN);init.visitMaxs(0,0);init.visitEnd();
        if(!candidate) {
            getter.name="lookup";
            for(AbstractInsnNode insn:getter.instructions.toArray())if(insn instanceof FieldInsnNode) {
                FieldInsnNode field=(FieldInsnNode)insn;
                if(!field.owner.equals(ENGINE)||!field.name.equals("otgBiomeIdsByWorld"))throw new AssertionError("getter changed");
                field.owner=BASE;
            }
            getter.localVariables=null;getter.accept(writer);
        } else {
            MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"lookup","(Ljava/lang/String;)"+ARRAY,null,null);
            method.visitCode();mapCall(method,"get","(Ljava/lang/Object;)Ljava/lang/Object;");
            method.visitTypeInsn(Opcodes.CHECKCAST,ARRAY);method.visitVarInsn(Opcodes.ASTORE,2);
            Label found=new Label();method.visitVarInsn(Opcodes.ALOAD,2);method.visitJumpInsn(Opcodes.IFNONNULL,found);
            mapCall(method,"containsKey","(Ljava/lang/Object;)Z");method.visitJumpInsn(Opcodes.IFNE,found);
            method.visitIntInsn(Opcodes.SIPUSH,2048);method.visitTypeInsn(Opcodes.ANEWARRAY,"com/pg85/otg/configuration/biome/BiomeConfig");method.visitInsn(Opcodes.ARETURN);
            method.visitLabel(found);method.visitVarInsn(Opcodes.ALOAD,2);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(0,0);method.visitEnd();
        }
        writer.visitEnd();return (Lookup)new Loader().define(writer.toByteArray()).newInstance();
    }
    private static void mapCall(MethodVisitor method,String name,String descriptor) {
        method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,BASE,"otgBiomeIdsByWorld","Ljava/util/HashMap;");
        method.visitVarInsn(Opcodes.ALOAD,1);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/HashMap",name,descriptor,false);
    }
    private static final class Loader extends ClassLoader {
        Loader(){super(OtgBiomeLookupProbe.class.getClassLoader());}
        Class<?> define(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}
    }
}
