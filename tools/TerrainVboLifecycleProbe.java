package dev.rlcraft.ice.hooks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Offline proof against the actual OptiFine-patched VertexBuffer lifecycle. */
public final class TerrainVboLifecycleProbe {
    public static void main(String[] args) throws Exception {
        String owner="net/minecraft/client/renderer/vertex/VertexBuffer";
        byte[] original=read(args[0],"cdz");
        byte[] patched;
        try(URLClassLoader loader=new URLClassLoader(new URL[]{new File(args[1]).toURI().toURL(),
            new File(args[0]).toURI().toURL()},TerrainVboLifecycleProbe.class.getClassLoader())) {
            Class<?> transformer=Class.forName("optifine.OptiFineClassTransformer",true,loader);
            patched=(byte[])transformer.getMethod("transform",String.class,String.class,byte[].class)
                .invoke(transformer.newInstance(),"cdz",owner.replace('/','.'),original);
        }
        require(!Arrays.equals(original,patched),"OptiFine patch absent");
        byte[] mapped=remap(patched,Paths.get(args[2]));
        byte[] adapted=new VanillaChunkRenderAdapter(VanillaChunkRenderAdapter.Part.VERTEX_BUFFER_ACCESS)
            .transform(owner.replace('/','.'),mapped,null);
        ClassNode node=new ClassNode(Opcodes.ASM5);new ClassReader(adapted).accept(node,0);
        MethodNode region=method(node,"setVboRegion"),delete=method(node,"func_177362_c");
        int deleteCall=-1,regionStore=-1,index=0;
        for(AbstractInsnNode insn:region.instructions.toArray()) {
            if(insn instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)insn;
                System.out.println("setVboRegion calls "+call.owner+"."+call.name+call.desc);
                if(call.owner.equals(owner)&&call.name.equals("func_177362_c")) deleteCall=index;
            }
            if(insn instanceof FieldInsnNode) {
                FieldInsnNode field=(FieldInsnNode)insn;
                if(field.getOpcode()==Opcodes.PUTFIELD&&field.desc.equals("Lnet/optifine/render/VboRegion;")) regionStore=index;
            }
            index++;
        }
        require(deleteCall>=0&&regionStore>deleteCall,"region transition does not delete before publication");
        int releaseCall=-1,nativeDelete=-1;index=0;
        for(AbstractInsnNode insn:delete.instructions.toArray()) {
            if(insn instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)insn;
                System.out.println("deleteGlBuffers calls "+call.owner+"."+call.name+call.desc);
                if(call.owner.equals(ModernTerrainAdapter.RENDER_BRIDGE)&&call.name.equals("releaseVertexBuffer")) releaseCall=index;
                if(call.owner.equals("net/minecraft/client/renderer/OpenGlHelper")) nativeDelete=index;
            }
            index++;
        }
        require(releaseCall>=0&&nativeDelete>releaseCall,"release hook does not precede native deletion");
        for(MethodNode candidate:node.methods) for(AbstractInsnNode insn:candidate.instructions.toArray()) {
            if(insn instanceof FieldInsnNode) {
                FieldInsnNode field=(FieldInsnNode)insn;
                if(field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals(owner)&&field.name.equals("field_177363_b"))
                    require(candidate.name.equals("<init>"),"format mutation outside constructor");
            }
        }
        MethodNode count=method(node,"ice$vertexCount");int reads=0;
        for(AbstractInsnNode insn:count.instructions.toArray()) if(insn instanceof FieldInsnNode) {
            FieldInsnNode field=(FieldInsnNode)insn;
            require(field.getOpcode()==Opcodes.GETFIELD&&field.name.equals("field_177364_c")&&field.desc.equals("I"),"wrong vertex count field");reads++;
        }
        require(reads==1,"count getter field read");
        System.out.println("actual_optifine_g5=true region_deletes_before_publish=true injected_release_before_native_delete=true constructor_only_format=true exact_vertex_count_getter=true");
    }
    static MethodNode method(ClassNode node,String name) {
        for(MethodNode method:node.methods)if(method.name.equals(name))return method;
        throw new AssertionError("missing "+name);
    }
    static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    static byte[] read(String path,String name)throws IOException {
        try(JarFile jar=new JarFile(path);InputStream in=jar.getInputStream(jar.getJarEntry(name+".class"))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];
            for(int n;(n=in.read(block))!=-1;)out.write(block,0,n);return out.toByteArray();
        }
    }
    static byte[] remap(byte[] original,Path tsrg)throws IOException {
        final Map<String,String> classes=new HashMap<>(),fields=new HashMap<>(),methods=new HashMap<>();
        String owner=null;
        for(String line:Files.readAllLines(tsrg,StandardCharsets.UTF_8)) {
            String[] parts=line.trim().split(" +");
            if(!Character.isWhitespace(line.charAt(0))){owner=parts[0];classes.put(owner,parts[1]);}
            else if(parts.length==2)fields.put(owner+"/"+parts[0],parts[1]);
            else methods.put(owner+"/"+parts[0]+parts[1],parts[2]);
        }
        ClassWriter output=new ClassWriter(0);
        new ClassReader(original).accept(new ClassRemapper(output,new Remapper(){
            public String map(String name){String value=classes.get(name);return value==null?name:value;}
            public String mapFieldName(String owner,String name,String desc){String value=fields.get(owner+"/"+name);return value==null?name:value;}
            public String mapMethodName(String owner,String name,String desc){String value=methods.get(owner+"/"+name+desc);return value==null?name:value;}
        }),0);return output.toByteArray();
    }
}
