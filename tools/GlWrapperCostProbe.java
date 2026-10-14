package dev.rlcraft.ice.hooks;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Native wrapper diagnostic using the production bytecode adapter. It does not
 * reproduce OptiFine/other transformers or establish whole-game FPS gains. */
public final class GlWrapperCostProbe {
    public static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final int CHUNKS = 2048;
    static {
        for (int i=0;i<16;i++) MATRIX.put(i,i%5==0?(i==15?1F:1.000001F):0F);
        for (int i=12;i<15;i++) MATRIX.put(i,8F*1.000001F-8F);
    }
    public static void main(String[] args) throws Throwable {
        Pbuffer context=new Pbuffer(16,16,new PixelFormat(),null,null);
        try {
            context.makeCurrent(); GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            if (!HudRenderBootstrap.install(HudRenderBridge.class)) throw new AssertionError("HUD bridge");
            MethodHandle plain=wrappers(false), hooked=wrappers(true);
            double[][] times=new double[3][101];
            for(int i=0;i<600;i++) { int mode=i%3; seed(mode); invoke(mode==0?plain:hooked); }
            for(int round=0;round<101;round++) for(int step=0;step<3;step++) {
                int mode=(round+step)%3; seed(mode); GL11.glFinish(); long begin=System.nanoTime();
                invoke(mode==0?plain:hooked); times[mode][round]=(System.nanoTime()-begin)/1e6;
            }
            for(int mode=0;mode<3;mode++) {
                Arrays.sort(times[mode]);
                System.out.printf(Locale.ROOT,"mode=%s chunks=%d cpu_p50_ms=%.6f cpu_p95_ms=%.6f%n",
                    mode==0?"original_wrappers":mode==1?"hooks_unknown_matrix":"hooks_known_matrix",
                    CHUNKS,times[mode][50],times[mode][95]);
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            System.out.println("adapter=production HUD=installed_no_active_scope renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }
    private static void invoke(MethodHandle handle) throws Throwable { handle.invokeExact(); }
    private static void seed(int mode) {
        if(mode==2) {
            FloatBuffer identity=BufferUtils.createFloatBuffer(16);
            for(int i=0;i<16;i++)identity.put(i,i%5==0?1F:0F);
            EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW,identity,identity);
        } else EarlyMatrixStateTracker.invalidate();
    }
    private static MethodHandle wrappers(boolean hooked) throws Exception {
        String old=GlStateTrackingAdapter.GL_STATE_MANAGER;
        Method factory=GlStateTrackingAdapterTest.class.getDeclaredMethod("syntheticManager",String.class);
        factory.setAccessible(true);
        ClassNode node=new ClassNode(Opcodes.ASM5);
        new ClassReader((byte[])factory.invoke(null,old)).accept(node,0);
        for(MethodNode method:node.methods) {
            String nativeName="func_179094_E".equals(method.name)?"glPushMatrix":
                "func_179121_F".equals(method.name)?"glPopMatrix":
                "func_179109_b".equals(method.name)?"glTranslatef":
                "func_179110_a".equals(method.name)?"glMultMatrix":null;
            if(nativeName==null)continue;
            method.instructions.clear(); method.tryCatchBlocks.clear();
            int local=0;
            for(Type type:Type.getArgumentTypes(method.desc)) {
                method.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD),local));
                local+=type.getSize();
            }
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/lwjgl/opengl/GL11",nativeName,method.desc,false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        ClassWriter raw=new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(raw);
        byte[] bytes=raw.toByteArray();
        if(hooked)bytes=new GlStateTrackingAdapter(GlStateTrackingAdapter.Part.GL_STATE_MANAGER).transform(old,bytes,
            new TargetSpec(old,"modern-visibility-hzb","probe",Collections.<String>emptySet()));
        node=new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
        node.name="dev/rlcraft/ice/hooks/ProbeMatrix"+(hooked?"Hooked":"Original");
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        MethodVisitor run=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"run","()V",null,null);
        run.visitCode(); run.visitInsn(Opcodes.ICONST_0); run.visitVarInsn(Opcodes.ISTORE,0);
        Label loop=new Label(),done=new Label(); run.visitLabel(loop);
        run.visitVarInsn(Opcodes.ILOAD,0); run.visitLdcInsn(CHUNKS); run.visitJumpInsn(Opcodes.IF_ICMPGE,done);
        run.visitMethodInsn(Opcodes.INVOKESTATIC,node.name,"func_179094_E","()V",false);
        run.visitInsn(Opcodes.FCONST_1); run.visitInsn(Opcodes.FCONST_1); run.visitInsn(Opcodes.FCONST_0);
        run.visitMethodInsn(Opcodes.INVOKESTATIC,node.name,"func_179109_b","(FFF)V",false);
        run.visitFieldInsn(Opcodes.GETSTATIC,"dev/rlcraft/ice/hooks/GlWrapperCostProbe","MATRIX","Ljava/nio/FloatBuffer;");
        run.visitMethodInsn(Opcodes.INVOKESTATIC,node.name,"func_179110_a","(Ljava/nio/FloatBuffer;)V",false);
        run.visitMethodInsn(Opcodes.INVOKESTATIC,node.name,"func_179121_F","()V",false);
        run.visitIincInsn(0,1);run.visitJumpInsn(Opcodes.GOTO,loop);run.visitLabel(done);
        run.visitInsn(Opcodes.RETURN);run.visitMaxs(0,0);run.visitEnd();writer.visitEnd();
        Class<?> type=new Loader().define(writer.toByteArray());
        return MethodHandles.publicLookup().findStatic(type,"run",MethodType.methodType(void.class));
    }
    private static final class Loader extends ClassLoader {
        Loader(){super(GlWrapperCostProbe.class.getClassLoader());}
        Class<?> define(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}
    }
}
