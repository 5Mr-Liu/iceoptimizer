package dev.rlcraft.ice.hooks;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;
import static org.junit.Assert.*;

public class NativeTerrainVboSelectionAdapterTest {
    @Test public void terrainIgnoresSavedOptionButKeepsHardwareGuardsAndGlobalMethod() throws Exception {
        String helper=NativeTerrainVboSelectionAdapter.HELPER;
        String settings="net/minecraft/client/settings/GameSettings";
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,helper,null,"java/lang/Object",null);
        w.visitField(9,"supported","Z",null,null).visitEnd();
        w.visitField(9,"settings","L"+settings+";",null,null).visitEnd();
        MethodVisitor m=w.visitMethod(9,"func_176075_f","()Z",null,null); m.visitCode();
        Label no=new Label(); m.visitFieldInsn(Opcodes.GETSTATIC,helper,"supported","Z");m.visitJumpInsn(Opcodes.IFEQ,no);
        m.visitFieldInsn(Opcodes.GETSTATIC,helper,"settings","L"+settings+";");
        m.visitFieldInsn(Opcodes.GETFIELD,settings,"field_178881_t","Z");m.visitInsn(Opcodes.IRETURN);
        m.visitLabel(no);m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(9,"func_77474_a","()V",null,null);m.visitCode();m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();
        byte[] original=w.toByteArray(), transformed=new NativeTerrainVboSelectionAdapter().transform("",original,null);
        assertEquals(dump(method(node(original),"func_176075_f")),dump(method(node(transformed),"func_176075_f")));
        Map<String,String> names=new HashMap<>();names.put(helper,"probe/TerrainHelper");
        names.put(settings,Type.getInternalName(Settings.class));names.put(NativeTerrainVboSelectionAdapter.STARTUP,Type.getInternalName(Startup.class));
        ClassWriter remapped=new ClassWriter(0);new ClassReader(transformed).accept(new ClassRemapper(remapped,new SimpleRemapper(names)),0);
        Class<?> type=new Loader().define(remapped.toByteArray()); type.getField("settings").set(null,new Settings());
        type.getMethod("func_77474_a").invoke(null);assertTrue(Startup.initialized);
        type.getField("supported").setBoolean(null,true);
        assertEquals(false,type.getMethod("func_176075_f").invoke(null));
        assertEquals(true,type.getMethod(NativeTerrainVboSelectionAdapter.TERRAIN_VBO).invoke(null));
        type.getField("supported").setBoolean(null,false);
        try {type.getMethod(NativeTerrainVboSelectionAdapter.TERRAIN_VBO).invoke(null);fail();}
        catch(java.lang.reflect.InvocationTargetException e){assertTrue(e.getCause() instanceof IllegalStateException);}
    }

    @Test public void realOptifineG5KeepsMultiTextureAndRegionGuards() throws Exception {
        try(OptifinePatchedClassSupport support=OptifinePatchedClassSupport.openOrSkip()) {
            byte[] original=support.patchAndRemap("cii","net.minecraft.client.renderer.OpenGlHelper");
            ClassNode before=node(original), after=node(new NativeTerrainVboSelectionAdapter().transform("",original,null));
            assertEquals(dump(method(before,"func_176075_f")),dump(method(after,"func_176075_f")));
            String cloned=dump(method(after,NativeTerrainVboSelectionAdapter.TERRAIN_VBO));
            assertTrue(cloned.contains("isMultiTexture"));assertTrue(cloned.contains("rejectUnsupportedMultiTexture"));assertTrue(cloned.contains("isRenderRegions"));
            assertTrue(cloned.contains("vboRegions"));assertTrue(cloned.contains("requireTerrainVbo"));
            assertFalse(cloned.contains("field_178881_t"));
            assertEquals(1,calls(method(after,"func_77474_a"),NativeTerrainVboSelectionAdapter.STARTUP,"initialize"));
        }
    }

    @Test public void realG5TerrainEntriesAndPauseCopiesAreScopedAfterAdapterChain() throws Exception {
        try(OptifinePatchedClassSupport support=OptifinePatchedClassSupport.openOrSkip()) {
            dev.rlcraft.ice.early.EarlyRendererOwnership.initialize(false,true);
            try {
                for(String[] entry:new String[][]{{"buy","net.minecraft.client.renderer.RenderGlobal"},
                    {"bxr","net.minecraft.client.renderer.chunk.RenderChunk"},{"bxm","net.minecraft.client.renderer.chunk.ChunkRenderDispatcher"},
                    {"bvf","net.minecraft.client.renderer.VboRenderList"},{"buk","net.minecraft.client.renderer.BufferBuilder"}}) {
                    byte[] bytes=support.patchAndRemap(entry[0],entry[1]);
                    for(TargetSpec target:OptimizerTargetCatalog.findAll(entry[1])) {
                        if(!OptimizerBootstrapPolicy.shouldInstall(target))continue;
                        OptimizerBytecodeAdapter adapter=OptimizerAdapterRegistry.find(target.adapterId);
                        if(adapter!=null)bytes=adapter.transform(entry[1],bytes,target);
                    }
                    ClassNode node=node(bytes);int terrainCalls=0;
                    for(MethodNode m:node.methods) {
                        terrainCalls+=calls(m,NativeTerrainVboSelectionAdapter.HELPER,NativeTerrainVboSelectionAdapter.TERRAIN_VBO);
                        if(entry[0].equals("buy") && (m.name.equals("<init>")||m.name.equals("func_72712_a"))) {
                            assertEquals(1,calls(m,NativeTerrainVboSelectionAdapter.HELPER,"func_176075_f"));
                            for(AbstractInsnNode i:m.instructions.toArray())if(i instanceof TypeInsnNode && i.getOpcode()==Opcodes.NEW) {
                                String allocated=((TypeInsnNode)i).desc;
                                assertFalse(allocated.endsWith("/RenderList"));assertFalse(allocated.endsWith("/ListChunkFactory"));
                            }
                        }
                        if(m.name.contains("func_174982_a")||m.name.contains("func_188245_a"))
                            assertEquals(m.name,0,calls(m,NativeTerrainVboSelectionAdapter.HELPER,"func_176075_f"));
                    }
                    if(entry[0].equals("buk"))assertNotNull(method(node,"ice$sortSprites"));
                    else if(entry[0].equals("bvf")) {
                        int materialCalls=0;
                        for(MethodNode m:node.methods)materialCalls+=calls(m,"dev/rlcraft/ice/optimizer/render/terrain/NativeTerrainPausedMaterials","draw");
                        assertEquals(2,materialCalls);
                    } else assertTrue(entry[1],terrainCalls>0);
                    // ASM's verifier can check the transformed control flow without loading Minecraft/GL.
                    new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0),false),0);
                }
            } finally {OptimizerBootstrapPolicy.initialize(null);}
        }
    }

    private static int calls(MethodNode method,String owner,String name) {
        int n=0;for(AbstractInsnNode i:method.instructions.toArray())if(i instanceof MethodInsnNode) {
            MethodInsnNode m=(MethodInsnNode)i;if(m.owner.equals(owner)&&m.name.equals(name))n++;
        }return n;
    }
    private static ClassNode node(byte[] bytes){ClassNode n=new ClassNode(Opcodes.ASM5);new ClassReader(bytes).accept(n,0);return n;}
    private static MethodNode method(ClassNode node,String name){for(MethodNode m:node.methods)if(m.name.equals(name))return m;throw new AssertionError(name);}
    private static String dump(MethodNode method){Textifier t=new Textifier();method.accept(new TraceMethodVisitor(t));StringWriter s=new StringWriter();t.print(new PrintWriter(s));return s.toString();}
    private static class Loader extends ClassLoader {Class<?> define(byte[] b){return defineClass(null,b,0,b.length);}}
    public static class Settings {public boolean field_178881_t;}
    public static class Startup {
        static boolean initialized;
        public static void initialize(){initialized=true;}
        public static boolean requireTerrainVbo(boolean allowed){if(!allowed)throw new IllegalStateException();return true;}
    }
}
