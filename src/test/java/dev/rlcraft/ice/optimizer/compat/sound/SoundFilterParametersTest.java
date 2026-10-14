package dev.rlcraft.ice.optimizer.compat.sound;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.Method;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Uses the production helper bytecode, substituting only the native EFX boundary offline. */
public class SoundFilterParametersTest {
    @Test public void identicalNativeWritesAreSkippedButChangesResetAndSlotCommitAreRetained() throws Exception {
        Class<?> helper = new RecordingLoader().loadClass(SoundFilterParameters.class.getName());
        Filter filter = new Filter(); RecordingEfx.calls = 0;
        Method scalar = helper.getMethod("alFilterf", int.class, int.class, float.class, Object.class, boolean.class);
        scalar.invoke(null, 7, 1, 0.5F, filter, true); assertEquals(1, RecordingEfx.calls);
        scalar.invoke(null, 7, 1, 0.5F, filter, true); assertEquals(1, RecordingEfx.calls);
        scalar.invoke(null, 7, 1, 0.75F, filter, true); assertEquals(2, RecordingEfx.calls);
        scalar.invoke(null, 8, 1, 0.75F, filter, true); assertEquals(3, RecordingEfx.calls);
        scalar.invoke(null, 8, 1, 0.75F, filter, false); assertEquals(4, RecordingEfx.calls);
        helper.getMethod("reset", Object.class).invoke(null, filter);
        scalar.invoke(null, 8, 1, 0.75F, filter, true); assertEquals(5, RecordingEfx.calls);
        scalar.invoke(null, 8, 1, Float.NaN, filter, true);
        scalar.invoke(null, 8, 1, Float.NaN, filter, true); assertEquals(7, RecordingEfx.calls);
        Method commit = helper.getMethod("alAuxiliaryEffectSloti", int.class, int.class, int.class, Object.class, boolean.class);
        commit.invoke(null, 3, 1, 7, filter, true); commit.invoke(null, 3, 1, 7, filter, true);
        assertEquals(9, RecordingEfx.calls);
    }
    public static final class RecordingEfx {
        static int calls;
        public static void alFilterf(int id,int parameter,float value) { calls++; }
        public static void alEffectf(int id,int parameter,float value) { calls++; }
        public static void alAuxiliaryEffectSlotf(int id,int parameter,float value) { calls++; }
        public static void alEffecti(int id,int parameter,int value) { calls++; }
        public static void alAuxiliaryEffectSloti(int id,int parameter,int value) { calls++; }
    }
    private static final class Filter implements SoundFilterAccess {
        Object state;
        public Object ice$soundParameters() { return state; }
        public void ice$soundParameters(Object state) { this.state=state; }
    }
    private static final class RecordingLoader extends ClassLoader {
        RecordingLoader() { super(SoundFilterParametersTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String helper = SoundFilterParameters.class.getName();
            if (!name.equals(helper) && !name.startsWith(helper + "$")) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) try {
                InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class");
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer=new byte[4096];
                for(int n; (n=in.read(buffer))!=-1;) out.write(buffer,0,n); in.close();
                ClassNode node=new ClassNode(Opcodes.ASM5); new ClassReader(out.toByteArray()).accept(node,0);
                for(MethodNode method:node.methods) for(AbstractInsnNode instruction:method.instructions.toArray())
                    if(instruction instanceof MethodInsnNode) {
                        MethodInsnNode call=(MethodInsnNode)instruction;
                        if(call.owner.equals("org/lwjgl/openal/EFX10")) call.owner=RecordingEfx.class.getName().replace('.','/');
                    }
                ClassWriter writer=new ClassWriter(0); node.accept(writer);byte[] bytes=writer.toByteArray();
                type=defineClass(name,bytes,0,bytes.length);
            } catch(IOException error) { throw new ClassNotFoundException(name,error); }
            if(resolve) resolveClass(type);return type;
        }
    }
}
