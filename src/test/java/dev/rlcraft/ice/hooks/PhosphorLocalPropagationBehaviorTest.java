package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import sun.misc.Unsafe;

/** Executes the actual original and transformed fetch/light methods against changing virtual storage. */
public class PhosphorLocalPropagationBehaviorTest {
    private static final String ENGINE=PhosphorLocalPropagationAdapter.TARGET;
    private static final String INFO=ENGINE+"$NeighborInfo";
    private static Map<Long, RecordingChunk> chunks;
    private static int chunkQueries;
    private static final Unsafe UNSAFE=unsafe();

    @Test public void actualMethodBytecodeMatchesAcrossBordersNullSectionsAndDynamicReplacement() throws Exception {
        ClassNode node=PhosphorLocalPropagationAdapterTest.transformed();
        Set<String> methods=new HashSet<String>(Arrays.asList("<clinit>", "fetchNeighborDataFromCursor",
            "ice$originalFetchNeighborData", "getCachedLightFor", "ice$getLocalCachedLightFor", "decodeWorldCoord"));
        node.methods.removeIf(m -> !methods.contains(m.name)); node.interfaces.clear(); node.innerClasses.clear();
        Set<String> fields=new HashSet<String>(Arrays.asList("curPos","curChunk","curChunkIdentifier","curData",
            "isNeighborDataValid","neighborInfos","neighborShifts","ITEMS_PROCESSED","CHUNKS_FETCHED"));
        node.fields.removeIf(f -> !fields.contains(f.name));
        MethodNode getChunk=new MethodNode(Opcodes.ACC_PRIVATE,"getChunk","(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/chunk/Chunk;",null,null);
        getChunk.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));
        getChunk.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,getClass().getName().replace('.','/'),"chunkFor",getChunk.desc,false));
        getChunk.instructions.add(new InsnNode(Opcodes.ARETURN));node.methods.add(getChunk);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        Loader loader=new Loader(remap(writer.toByteArray()),remap(PhosphorLocalPropagationAdapterTest.read(System.getProperty("ice.phosphor.jar"),INFO)));
        Class<?> engine=loader.loadClass(ENGINE.replace('/','.')), infoType=loader.loadClass(INFO.replace('/','.'));
        Method old=engine.getDeclaredMethod("ice$originalFetchNeighborData",EnumSkyBlock.class); old.setAccessible(true);
        Method optimized=engine.getDeclaredMethod("fetchNeighborDataFromCursor",EnumSkyBlock.class); optimized.setAccessible(true);
        OptimizerRegistry.breaker(OptimizationModule.PHOSPHOR_LOCAL_PROPAGATION).configure(true,3);
        OptimizerRegistry.breaker(OptimizationModule.PHOSPHOR_LOCAL_PROPAGATION).patchInstalled("test","test");
        try {
            int[] edges={-30000000,-33,-32,-17,-16,-15,-1,0,1,14,15,16,17,29999999};
            // scheduleLightUpdate accepts packed values outside 0..255. The
            // cursor is decoded first; a neighbor can carry back into range.
            int[] heights={-1,0,1,14,15,16,17,127,128,239,240,254,255,256,257};
            for(int x:edges)for(int y:heights)for(int z:new int[]{-17,-1,0,15,16,29999999}) {
                for(boolean nullSections:new boolean[]{false,true}) {
                    setup(x,z,nullSections);
                    Object a=engine(engine,infoType,x,y,z); old.invoke(a,nullSections?EnumSkyBlock.SKY:EnumSkyBlock.BLOCK);
                    List<String> expected=snapshot(engine,a);int expectedCalls=chunkQueries,expectedReads=reads();
                    setup(x,z,nullSections);
                    Object b=engine(engine,infoType,x,y,z); optimized.invoke(b,nullSections?EnumSkyBlock.SKY:EnumSkyBlock.BLOCK);
                    assertEquals(x+","+y+","+z,expected,snapshot(engine,b));
                    assertEquals(expectedCalls,chunkQueries);assertEquals(expectedReads,reads());
                    optimized.invoke(b,EnumSkyBlock.BLOCK);assertEquals(expectedReads,reads());
                }
            }
        } finally {OptimizerRegistry.breaker(OptimizationModule.PHOSPHOR_LOCAL_PROPAGATION).configure(false,3);}
    }
    private static Object engine(Class<?> type,Class<?> info,int x,int y,int z)throws Exception {
        Object object=UNSAFE.allocateInstance(type);long packed=encode(x,y,z);
        // Match nextItem/decodeWorldCoord, including an out-of-height queue item.
        set(type,object,"curPos",new BlockPos.MutableBlockPos(
            (int)((packed>>26)&67108863L)-33554432,(int)((packed>>52)&255L),
            (int)(packed&67108863L)-33554432));
        set(type,object,"curData",packed);set(type,object,"curChunkIdentifier",packed&4503598620737520L);
        set(type,object,"curChunk",chunks.get(key(x>>4,z>>4)));Object array=Array.newInstance(info,6);
        for(int i=0;i<6;i++){Object item=UNSAFE.allocateInstance(info);set(info,item,"pos",new BlockPos.MutableBlockPos(-77,-88,-99));Array.set(array,i,item);}
        set(type,object,"neighborInfos",array);return object;
    }
    private static List<String> snapshot(Class<?> type,Object object)throws Exception {
        Object array=get(type,object,"neighborInfos");List<String> result=new ArrayList<String>();
        for(int i=0;i<6;i++){Object info=Array.get(array,i);Class<?> c=info.getClass();BlockPos pos=(BlockPos)get(c,info,"pos");
            RecordingChunk chunk=(RecordingChunk)get(c,info,"chunk");RecordingStorage storage=(RecordingStorage)get(c,info,"section");
            result.add(get(c,info,"key")+":"+pos.getX()+","+pos.getY()+","+pos.getZ()+":"+(chunk==null?"null":chunk.cx+","+chunk.cz)
                +":"+(storage==null?"null":storage.seed)+":"+get(c,info,"light"));}
        return result;
    }
    private static void setup(int x,int z,boolean empty)throws Exception {
        chunks=new HashMap<Long,RecordingChunk>();chunkQueries=0;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            if(dx==1&&dz==0)continue; // Preserve the original missing-neighbor behavior.
            RecordingChunk c=(RecordingChunk)UNSAFE.allocateInstance(RecordingChunk.class);c.cx=(x>>4)+dx;c.cz=(z>>4)+dz;
            c.sections=new ExtendedBlockStorage[16];c.empty=empty;chunks.put(key(c.cx,c.cz),c);
            if(!empty)for(int section=0;section<16;section++)c.sections[section]=storage(c,section,section+3);
        }
    }
    private static RecordingStorage storage(RecordingChunk owner,int index,int seed)throws Exception {
        RecordingStorage s=(RecordingStorage)UNSAFE.allocateInstance(RecordingStorage.class);s.owner=owner;s.index=index;s.seed=seed;return s;
    }
    public static Chunk chunkFor(BlockPos pos){chunkQueries++;return chunks.get(key(pos.getX()>>4,pos.getZ()>>4));}
    private static int reads(){int n=0;for(RecordingChunk c:chunks.values())n+=c.reads;return n;}
    public static class RecordingChunk extends Chunk {
        int cx,cz,reads;boolean empty;ExtendedBlockStorage[] sections;
        private RecordingChunk(){super(null,0,0);}
        @Override public ExtendedBlockStorage[] getBlockStorageArray(){reads++;return sections;}
        @Override public boolean canSeeSky(BlockPos pos){return ((pos.getX()+pos.getY()+pos.getZ())&1)==0;}
    }
    public static class RecordingStorage extends ExtendedBlockStorage {
        RecordingChunk owner;int index,seed;
        private RecordingStorage(){super(0,false);}
        @Override public int getBlockLight(int x,int y,int z){
            // A real virtual callback replaces storage; the next neighbor must read the new section.
            try{owner.sections[index]=storage(owner,index,seed+1);}catch(Exception e){throw new RuntimeException(e);}
            return (seed+x*3+y*5+z*7)&15;
        }
    }
    private static byte[] remap(byte[] bytes) {
        final Map<String,String> names=new HashMap<String,String>();
        String[][] pairs={{"field_82609_l","VALUES"},{"func_176730_m","getDirectionVec"},{"func_177958_n","getX"},
            {"func_177956_o","getY"},{"func_177952_p","getZ"},{"func_181079_c","setPos"},{"field_186036_a","NULL_BLOCK_STORAGE"},
            {"func_76587_i","getBlockStorageArray"},{"func_177444_d","canSeeSky"},{"field_77198_c","defaultLightValue"},
            {"func_177412_p","getWorld"},{"field_73011_w","provider"},{"func_191066_m","hasSkyLight"},
            {"func_76670_c","getSkyLight"},{"func_76674_d","getBlockLight"}};
        for(String[] pair:pairs)names.put(pair[0],pair[1]);ClassWriter writer=new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer,new Remapper(){
            @Override public String mapMethodName(String owner,String name,String descriptor){return owner.startsWith("net/minecraft/")&&names.containsKey(name)?names.get(name):name;}
            @Override public String mapFieldName(String owner,String name,String descriptor){return owner.startsWith("net/minecraft/")&&names.containsKey(name)?names.get(name):name;}
        }),0);return writer.toByteArray();
    }
    private static final class Loader extends ClassLoader {
        final byte[] engine,info;Loader(byte[] a,byte[] b){super(PhosphorLocalPropagationBehaviorTest.class.getClassLoader());engine=a;info=b;}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            byte[] bytes=name.equals(ENGINE.replace('/','.'))?engine:name.equals(INFO.replace('/','.'))?info:null;
            if(bytes==null)return super.loadClass(name,resolve);Class<?> type=findLoadedClass(name);
            if(type==null)type=defineClass(name,bytes,0,bytes.length);if(resolve)resolveClass(type);return type;
        }
    }
    private static long encode(int x,int y,int z){return (long)y<<52|((long)x+33554432L)<<26|(long)z+33554432L;}
    private static long key(int x,int z){return (long)x&0xffffffffL|(long)z<<32;}
    private static void set(Class<?> type,Object object,String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object get(Class<?> type,Object object,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return (Unsafe)f.get(null);}catch(Exception e){throw new RuntimeException(e);}}
}
