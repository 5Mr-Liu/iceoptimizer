package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.resource.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offline old/new comparison. No finish/readback between the queued small batches. */
public final class HudStreamingNativeProbe {
    private static final int SIZE=96, DRAWS=4096;
    private static int texture;
    private static LwjglHudRenderer hud;
    private static LwjglFbpPacketRenderer fbp;
    private static final BufferBuilder PACKET=new BufferBuilder(64*112);
    private static final float[] GLYPH=new float[20];
    private static long creates,polls,deletes,fallbacks;

    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"new":args[0];
        Pbuffer context=new Pbuffer(SIZE,SIZE,new PixelFormat(),null,null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit=GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit=GL13.GL_TEXTURE1;
            texture=GL11.glGenTextures();
            ByteBuffer white=BufferUtils.createByteBuffer(4);white.putInt(-1).flip();
            for(int unit=0;unit<2;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,white);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glLoadIdentity();
            }
            RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
            CacheBudget budget=new CacheBudget(1<<20,16<<20,16<<20);
            ResourceLedger ledger=new ResourceLedger(guard,budget,new LwjglResourceDestroyer(),128);
            hud=new LwjglHudRenderer(guard,ledger,budget,64);
            fbp=new LwjglFbpPacketRenderer(guard,ledger,64*112);
            hud.prepare(1,1);fbp.prepare(1,1);
            setup();
            for(int i=0;i<16;i++) batch(i,4,false);
            GL11.glFinish();ledger.collect(1,128);ledger.collect(1,128);

            setup();
            for(int i=0;i<DRAWS;i++) batch(i,1+(i%4),true);
            byte[] expected=pixels();
            setup();
            FenceObserver hudObserver=new FenceObserver(hud),fbpObserver=new FenceObserver(fbp);
            // Clear warm-up fences from the counters but let the ordinary
            // acquire path actually poll/destroy them in the recorded run.
            hudObserver.observe(false);fbpObserver.observe(false);
            for(int i=0;i<DRAWS;i++) {
                batch(i,1+(i%4),false);
                (i%2==0?hudObserver:fbpObserver).observe(true);
            }
            byte[] actual=pixels();
            require(Arrays.equals(expected,actual),"queued overwrite changed output");
            long countedCreates=creates,countedPolls=polls,countedDeletes=deletes;
            if("new".equals(mode)) require(creates==0 && polls==0 && deletes==0 && fallbacks==0,
                "ordinary streamed batches still synchronized/fell back");

            // No reflective observation in the timed loop. Finish is outside
            // the measured submission interval and only separates full rounds.
            long[] ns=new long[11];
            for(int round=-4;round<ns.length;round++) {
                setup();GL11.glViewport(0,0,1,1);GL11.glFinish();
                long begin=System.nanoTime();
                for(int i=0;i<DRAWS;i++)batch(i,1+(i%4),false);
                long elapsed=System.nanoTime()-begin;
                GL11.glFinish();if(round>=0)ns[round]=elapsed;
            }
            Arrays.sort(ns);
            setup();
            for(int i=0;i<256;i++)batch(i,1<<((i/2)%7),true);
            byte[] growthExpected=pixels();
            setup();
            for(int i=0;i<256;i++)batch(i,1<<((i/2)%7),false);
            require(Arrays.equals(growthExpected,pixels()),"queued expansion changed output");
            // A real retirement still owns an actual fence and storage bytes.
            hud.close(true);fbp.close(true);
            require(budget.snapshot().getGpuUsed()>0,"retirement released bytes before collection");
            GL11.glFinish();ledger.collect(1,4096);ledger.collect(1,4096);ledger.destroyAll(1);
            require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0,"resource leak");
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error");
            System.out.printf(java.util.Locale.ROOT,
                "PASS mode=%s queued_batches=%d pixel_bytes=%d identical=true queued_growth_to_64_quads=true sync_create=%d sync_poll=%d sync_delete=%d median_submission_ns_per_batch=%.1f rounds=%d retire_gpu_direct_zero=true renderer=%s%n",
                mode,DRAWS,actual.length,countedCreates,countedPolls,countedDeletes,ns[ns.length/2]/(double)DRAWS,
                ns.length,GL11.glGetString(GL11.GL_RENDERER));
            GL11.glDeleteTextures(texture);
        } finally {context.destroy();}
    }

    private static void batch(int index,int count,boolean legacy) {
        int tile=index*73%576;
        float x=(tile%24)/12F-1F,y=(tile/24)/12F-1F,w=1F/12F;
        int r=32+(index*17%224),g=32+(index*53%224),b=32+(index*91%224);
        GL11.glColor4ub((byte)r,(byte)g,(byte)b,(byte)255);
        EarlyGlStateTracker.color(r/255F,g/255F,b/255F,1);
        if(legacy) {immediate(x,y,w,count);return;}
        if((index&1)==0) {
            if((index&2)==0) {
                for(int i=0;i<count;i++) require(hud.recordQuad(x,y,0,0,0,x+w,y+w,1,1),"HUD record");
            } else {
                for(int c=0;c<4;c++) {
                    int corner=c==2?3:c==3?2:c,offset=c*5;
                    GLYPH[offset]=corner==1||corner==2?x+w:x;
                    GLYPH[offset+1]=corner>=2?y+w:y;
                    GLYPH[offset+2]=0;GLYPH[offset+3]=corner==1||corner==2?1:0;GLYPH[offset+4]=corner>=2?1:0;
                }
                for(int i=0;i<count;i++)require(hud.recordGlyph(GLYPH),"HUD glyph record");
            }
            LwjglHudRenderer.FlushResult result=hud.flush(EarlyGlStateTracker.snapshot());
            require(hud.getLastError()==null,"HUD error");
            require(result==LwjglHudRenderer.FlushResult.MODERN || result==LwjglHudRenderer.FlushResult.LEGACY_BUSY,"HUD result");
            if(result!=LwjglHudRenderer.FlushResult.MODERN)fallbacks++;
        } else {
            PACKET.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
            for(int i=0;i<count;i++) for(int c=0;c<4;c++) PACKET
                .pos(c==1||c==2?x+w:x,c>=2?y+w:y,0).color(r,g,b,255)
                .tex(c==1||c==2?1:0,c>=2?1:0).lightmap(0,0).endVertex();
            LwjglFbpPacketRenderer.SubmitResult result=fbp.submitRawPacket(PACKET,
                EarlyGlStateTracker.snapshot(),EarlyGlStateTracker.compatibilitySnapshot());
            require(fbp.getLastError()==null,"FBP error");
            require(result==LwjglFbpPacketRenderer.SubmitResult.MODERN || result==LwjglFbpPacketRenderer.SubmitResult.LEGACY_BUSY,"FBP result");
            if(result!=LwjglFbpPacketRenderer.SubmitResult.MODERN) {
                fallbacks++;immediate(x,y,w,count);PACKET.finishDrawing();PACKET.reset();
            }
        }
    }
    private static void immediate(float x,float y,float w,int count) {
        GL11.glBegin(GL11.GL_QUADS);
        for(int i=0;i<count;i++)for(int c=0;c<4;c++) {
            GL11.glTexCoord2f(c==1||c==2?1:0,c>=2?1:0);
            GL11.glVertex3f(c==1||c==2?x+w:x,c>=2?y+w:y,0);
        }
        GL11.glEnd();
    }
    private static void setup() {
        GL20.glUseProgram(0);GL30.glBindVertexArray(0);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
        GL11.glViewport(0,0,SIZE,SIZE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_FOG);GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glColor4f(1,1,1,1);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        EarlyGlStateTracker.beginProbe();EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(GL30.GL_FRAMEBUFFER,0);EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER,0);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);EarlyGlStateTracker.lightingEnabled(false);
        EarlyGlStateTracker.seedDrawState(0,texture,texture,false,GL11.GL_ONE,GL11.GL_ZERO,
            GL11.GL_ONE,GL11.GL_ZERO,false,true,false,15,1,1,1,1);
        EarlyGlStateTracker.seedHudState(true,true,0,0,SIZE,SIZE);
        int[] textures=new int[32];textures[0]=textures[1]=texture;
        boolean[] enabled=new boolean[32];enabled[0]=enabled[1]=true;
        EarlyGlStateTracker.seedCompatibilityState(0,true,0,0,2,textures,enabled,
            GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD,GL11.GL_BACK,false,false,0,0,SIZE,SIZE);
    }
    private static byte[] pixels() {
        ByteBuffer pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        byte[] bytes=new byte[pixels.remaining()];pixels.get(bytes);return bytes;
    }
    private static final class FenceObserver {
        private final Object[] slots;
        private final Field fence;
        FenceObserver(Object renderer) throws Exception {
            Field f=renderer.getClass().getDeclaredField("slots");f.setAccessible(true);slots=(Object[])f.get(renderer);
            fence=slots[0].getClass().getDeclaredField("fence");fence.setAccessible(true);
        }
        void observe(boolean count) throws Exception {
            for(Object slot:slots) {
                ResourceLedger.RetirementFence value=(ResourceLedger.RetirementFence)fence.get(slot);
                if(value!=null && !(value instanceof CountedFence)) {
                    if(count)creates++;
                    fence.set(slot,new CountedFence(value));
                }
            }
        }
    }
    private static final class CountedFence implements ResourceLedger.RetirementFence {
        final ResourceLedger.RetirementFence wrapped;
        CountedFence(ResourceLedger.RetirementFence wrapped){this.wrapped=wrapped;}
        public boolean isSignaled(){polls++;return wrapped.isSignaled();}
        public void destroy(){deletes++;wrapped.destroy();}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
