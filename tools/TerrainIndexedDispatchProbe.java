import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import org.lwjgl.*;
import org.lwjgl.opengl.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/** Offline experiment only. Original arm uses arrays; candidate uses identical quad indices. */
public final class TerrainIndexedDispatchProbe {
    private static boolean enabled;
    private static int buffer, capacity;
    private static IntBuffer commands;
    public static void select(boolean value) { enabled=value; }
    public static void draw(int mode,int first,int count) {
        if(!enabled||mode!=GL11.GL_QUADS){GL11.glDrawArrays(mode,first,count);return;}
        prepare(count);
        GL32.glDrawElementsBaseVertex(mode,count,GL11.GL_UNSIGNED_INT,0L,first);
    }
    public static void multi(int mode,IntBuffer first,IntBuffer counts) {
        if(!enabled||mode!=GL11.GL_QUADS){GL14.glMultiDrawArrays(mode,first,counts);return;}
        int max=0;for(int i=counts.position();i<counts.limit();i++)max=Math.max(max,counts.get(i));prepare(max);
        int size=counts.remaining();
        if(commands==null||commands.capacity()<size*5)commands=BufferUtils.createIntBuffer(size*5);
        commands.clear();
        for(int i=0;i<size;i++) commands.put(counts.get(counts.position()+i)).put(1).put(0).put(first.get(first.position()+i)).put(0);
        commands.flip();
        GL43.glMultiDrawElementsIndirect(mode,GL11.GL_UNSIGNED_INT,commands,size,0);
    }
    private static void prepare(int count) {
        if(buffer==0)buffer=GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,buffer);
        if(count<=capacity)return;
        capacity=1;while(capacity<count)capacity<<=1;
        IntBuffer indices=BufferUtils.createIntBuffer(capacity);for(int i=0;i<capacity;i++)indices.put(i);indices.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
    }
    public static void main(String[] args)throws Exception {
        ClassLoader loader=new ClassLoader(TerrainIndexedDispatchProbe.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(!name.startsWith("dev.rlcraft.ice."))return super.loadClass(name,resolve);
                Class<?> found=findLoadedClass(name);
                if(found==null)try(InputStream in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                    if(in==null)throw new ClassNotFoundException(name);
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];for(int n;(n=in.read(block))>=0;)out.write(block,0,n);
                    byte[] bytes=out.toByteArray();
                    final boolean arena=name.endsWith(".LwjglTerrainArena"), engine=name.endsWith(".TerrainAlgorithmNativeProbe$Engine");
                    if(arena||engine) {
                        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                        reader.accept(new ClassVisitor(Opcodes.ASM5,writer) {
                            @Override public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] exceptions) {
                                MethodVisitor visitor=super.visitMethod(access,method,desc,sig,exceptions);
                                if(engine&&method.equals("draw"))return new AdviceAdapter(Opcodes.ASM5,visitor,access,method,desc) {
                                    @Override protected void onMethodEnter(){loadArg(0);visitMethodInsn(INVOKESTATIC,"TerrainIndexedDispatchProbe","select","(Z)V",false);}
                                };
                                if(!arena)return visitor;
                                return new MethodVisitor(Opcodes.ASM5,visitor) {
                                    @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf) {
                                        if(owner.equals("org/lwjgl/opengl/GL11")&&name.equals("glDrawArrays")) {owner="TerrainIndexedDispatchProbe";name="draw";}
                                        if(owner.equals("org/lwjgl/opengl/GL14")&&name.equals("glMultiDrawArrays")) {owner="TerrainIndexedDispatchProbe";name="multi";}
                                        super.visitMethodInsn(opcode,owner,name,descriptor,itf);
                                    }
                                };
                            }
                        },ClassReader.EXPAND_FRAMES);bytes=writer.toByteArray();
                    }
                    found=defineClass(name,bytes,0,bytes.length);
                }catch(IOException failure){throw new ClassNotFoundException(name,failure);}
                if(resolve)resolveClass(found);return found;
            }
        };
        try{Class.forName("dev.rlcraft.ice.optimizer.render.terrain.TerrainAlgorithmNativeProbe",true,loader).getMethod("main",String[].class).invoke(null,(Object)args);}
        catch(InvocationTargetException failure){throw new RuntimeException(failure.getCause());}
    }
}
