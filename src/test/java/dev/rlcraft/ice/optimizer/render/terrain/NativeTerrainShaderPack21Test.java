package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.Method;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.Assert.*;

public class NativeTerrainShaderPack21Test {
    @Test public void shiftedPointersMatchTheInstalledG5Layout() throws Exception {
        String path = System.getProperty("ice.optifine.jar", "");
        Assume.assumeTrue(!path.isEmpty());
        List<String> original;
        try (JarFile jar = new JarFile(path)) {
            ClassReader reader = new ClassReader(jar.getInputStream(jar.getJarEntry("net/optifine/shaders/ShadersRender.class")));
            Driver.reset(0);
            invoke(reader, "setupArrayPointersVbo", new Class<?>[0]);
            original = new ArrayList<>(Driver.pointers);
        }
        assertEquals(8, original.size());
        // Large base proves this is rebasing pointers, not truncating an index.
        long base = 70000L * 56;
        Driver.reset(base);
        invoke(new ClassReader(NativeTerrainShaderPack.class.getName()), "setupArrays21",
            new Class<?>[]{long.class}, base);
        Collections.sort(original); Collections.sort(Driver.pointers);
        assertEquals(original, Driver.pointers);
    }

    private static void invoke(ClassReader reader, String method, Class<?>[] signature, Object... args) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5); reader.accept(node, 0);
        for (Iterator<MethodNode> it = node.methods.iterator(); it.hasNext();)
            if (!it.next().name.equals(method)) it.remove();
        node.fields.clear();
        String driver = Type.getInternalName(Driver.class);
        for (MethodNode m : node.methods) for (AbstractInsnNode instruction : m.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) instruction;
                if (f.name.equals("MID_UV")) f.name = "midTexCoordAttrib";
                if (f.name.equals("TANGENT")) f.name = "tangentAttrib";
                if (f.name.equals("ENTITY")) f.name = "entityAttrib";
                if (f.owner.equals("cii")) f.name = f.name.equals("r") ? "lightmapTexUnit" : "defaultTexUnit";
                f.owner = driver;
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("cii")) call.name = "setClientActiveTexture";
                call.owner = driver;
            }
        }
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Class<?> type = new ClassLoader(NativeTerrainShaderPack21Test.class.getClassLoader()) {
            Class<?> define(byte[] b) { return defineClass(null, b, 0, b.length); }
        }.define(writer.toByteArray());
        Method m = type.getDeclaredMethod(method, signature); m.setAccessible(true); m.invoke(null, args);
    }

    public static final class Driver {
        public static int defaultTexUnit=0x84C0, lightmapTexUnit=0x84C1;
        public static int midTexCoordAttrib=11, tangentAttrib=12, entityAttrib=10;
        static final List<String> pointers=new ArrayList<>();
        static int texture; static long base;
        static void reset(long shift) { pointers.clear(); texture=defaultTexUnit; base=shift; }
        public static void setClientActiveTexture(int unit) { texture=unit; }
        static void record(String kind,int size,int type,int stride,long offset) {
            pointers.add(kind+":"+size+":"+type+":"+stride+":"+(offset-base));
        }
        public static void glVertexPointer(int s,int t,int stride,long p) { record("vertex",s,t,stride,p); }
        public static void glColorPointer(int s,int t,int stride,long p) { record("color",s,t,stride,p); }
        public static void glTexCoordPointer(int s,int t,int stride,long p) { record("uv"+texture,s,t,stride,p); }
        public static void glNormalPointer(int t,int stride,long p) { record("normal",3,t,stride,p); }
        public static void glVertexAttribPointer(int a,int s,int t,boolean n,int stride,long p) {
            record("attrib"+a+":"+n,s,t,stride,p);
        }
    }
}
