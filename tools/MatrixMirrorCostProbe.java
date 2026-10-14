package dev.rlcraft.ice.optimizer.compat.gl;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Isolates matrix mirror CPU cost around real native matrix calls. This is a
 * diagnostic lower bound, not the fully transformed game or a whole-frame gain. */
public final class MatrixMirrorCostProbe {
    private static final int CHUNKS=2048;
    private static final FloatBuffer IDENTITY=BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODEL=BufferUtils.createFloatBuffer(16);
    static {
        for(int i=0;i<16;i++){IDENTITY.put(i,i%5==0?1F:0F);MODEL.put(i,i%5==0?(i==15?1F:1.000001F):0F);}
        for(int i=12;i<15;i++)MODEL.put(i,8F*1.000001F-8F);
    }
    public static void main(String[] args)throws Exception {
        Pbuffer context=new Pbuffer(16,16,new PixelFormat(),null,null);
        try {
            context.makeCurrent();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
            double[][] times=new double[3][101];
            for(int i=0;i<600;i++){int mode=i%3;seed(mode);run(mode);}
            for(int round=0;round<101;round++)for(int step=0;step<3;step++) {
                int mode=(round+step)%3;seed(mode);GL11.glFinish();long begin=System.nanoTime();
                run(mode);times[mode][round]=(System.nanoTime()-begin)/1e6;
            }
            for(int mode=0;mode<3;mode++) {
                Arrays.sort(times[mode]);System.out.printf(Locale.ROOT,"mode=%s chunks=%d cpu_p50_ms=%.6f cpu_p95_ms=%.6f%n",
                    mode==0?"native_only":mode==1?"unknown_mirror":"known_mirror",CHUNKS,times[mode][50],times[mode][95]);
            }
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            System.out.println("invalidations="+EarlyMatrixStateTracker.invalidations()+" renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally {context.destroy();}
    }
    private static void seed(int mode) {
        if(mode==2)EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW,IDENTITY,IDENTITY);
        else EarlyMatrixStateTracker.invalidate();
    }
    private static void run(int mode) {
        for(int i=0;i<CHUNKS;i++) {
            GL11.glPushMatrix();if(mode!=0)EarlyMatrixStateTracker.pushMatrix();
            GL11.glTranslatef(i&15,i&7,0);if(mode!=0)EarlyMatrixStateTracker.translate(i&15,i&7,0F);
            GL11.glMultMatrix(MODEL);if(mode!=0)EarlyMatrixStateTracker.multMatrix(MODEL);
            GL11.glPopMatrix();if(mode!=0)EarlyMatrixStateTracker.popMatrix();
        }
    }
}
