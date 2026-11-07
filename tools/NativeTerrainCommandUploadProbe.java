package dev.rlcraft.ice.optimizer.render.terrain;

import java.lang.reflect.Field;
import java.nio.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Real-driver command upload checks/timing, not a Minecraft FPS benchmark. */
public final class NativeTerrainCommandUploadProbe {
    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(32,32,new PixelFormat(),null,null);
        try {
            context.makeCurrent(); NativeTerrainStartup.initialize();
            System.out.println("renderer="+GL11.glGetString(GL11.GL_RENDERER));
            NativeTerrainGl43 backend = new NativeTerrainGl43(28);
            Field field=NativeTerrainGl43.class.getDeclaredField("commands");field.setAccessible(true);
            NativeTerrainStorage storage=(NativeTerrainStorage)field.get(backend);
            int sentinel=GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,sentinel);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,sentinel);
            try {
                int[] words=new int[8192*9]; Random random=new Random(88021);
                // Every packet must survive exact replay, prefix-only changes,
                // size changes, suffix changes and restoration of older data.
                for(int frame=0;frame<240;frame++) {
                    int draws=frame%20<10?1024:8192;
                    if(frame%3!=0)words[random.nextInt(draws*4)]=random.nextInt();
                    if(frame%7==0)words[draws*4+random.nextInt(draws*5)]=random.nextInt();
                    backend.prepare(words,draws*36);
                    checkBindings(sentinel);
                    ByteBuffer actual=BufferUtils.createByteBuffer(draws*36);
                    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,storage.buffer);
                    GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER,0,actual);
                    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,sentinel);
                    for(int i=0;i<draws*9;i++)if(actual.getInt(i*4)!=words[i])throw new AssertionError("packet "+frame+" word "+i);
                }
                for(String mode:new String[]{"unchanged","camera","commands"}) {
                    int draws=4096; long[] samples=new long[800];
                    backend.prepare(words,draws*36);
                    for(int frame=-200;frame<samples.length;frame++) {
                        if(mode.equals("camera"))for(int i=0;i<draws*4;i+=4)words[i]=Float.floatToRawIntBits(frame+i*.5F);
                        if(mode.equals("commands"))words[draws*4+2]++;
                        long start=System.nanoTime();
                        backend.prepare(words,draws*36);
                        // Probe only: include completion, not just queued work.
                        GL11.glFinish();
                        if(frame>=0)samples[frame]=System.nanoTime()-start;
                    }
                    Arrays.sort(samples);
                    System.out.printf(Locale.ROOT,"mode=%s draws=%d p50_ms=%.5f p95_ms=%.5f p99_ms=%.5f max_ms=%.5f%n",mode,draws,samples[400]/1e6,samples[760]/1e6,samples[792]/1e6,samples[799]/1e6);
                }
                checkBindings(sentinel);
                if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new AssertionError("GL error");
                System.out.println("PASS: 240 exact resident packets, growth/shrink, replay, camera changes, command changes, bindings");
            } finally {backend.delete();GL15.glDeleteBuffers(sentinel);}
        } finally {context.destroy();}
    }
    private static void checkBindings(int expected) {
        if(GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING)!=expected
            ||GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING)!=expected)throw new AssertionError("binding leak");
    }
}
