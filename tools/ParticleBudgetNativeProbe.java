package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.*;
import dev.rlcraft.ice.optimizer.render.resource.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Offline native proof of budget-pressure fallback and recovery, never run in game. */
public final class ParticleBudgetNativeProbe {
    private static final double[] CORNERS = { -.5,-.5,0, -.5,.5,0, .5,.5,0, .5,-.5,0 };
    private static int texture;
    private static ParticleState state;

    public static void main(String[] arguments) throws Exception {
        Pbuffer context = new Pbuffer(32,32,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            texture = GL11.glGenTextures();
            ByteBuffer white = BufferUtils.createByteBuffer(4);
            white.putInt(-1).flip();
            for (int unit=0; unit<2; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,white);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glLoadIdentity();
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glViewport(0,0,32,32); GL11.glColor4f(1,1,1,1);
            state = new ParticleState(0,texture,texture,0,false,GL11.GL_ONE,GL11.GL_ZERO,
                GL11.GL_ONE,GL11.GL_ZERO,false,true,false,15,false,0);
            run(131072);
            GL11.glDeleteTextures(texture);
            if (GL11.glGetError()!=GL11.GL_NO_ERROR) throw new AssertionError("GL error");
            System.out.println("PASS pipeline_at_full_payload_budget=true small_batch_capacity=true pressure_legacy_bytes=true recovery_without_reset=true growth_pixels_equal=true gpu_direct_budget_zero=true renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void run(int maximum) {
        CacheBudget budget = new CacheBudget(1<<20,64<<20,512<<10);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard,budget,new LwjglResourceDestroyer(),128);
        LwjglParticleRenderer renderer = new LwjglParticleRenderer(guard,ledger,budget,maximum);
        CacheBudget.Reservation pressure=budget.tryReserve(BudgetKind.GPU,512<<10);
        if (!renderer.initializeGpuResources(1,1) || renderer.getLastError()!=null)
            throw new AssertionError("opaque pipeline objects must not require invented payload bytes");
        pressure.close();
        if (!renderer.initializeGpuResources(1,1)) throw new AssertionError("pipeline did not recover");
        long pipeline = budget.snapshot().getGpuUsed();
        pressure=budget.tryReserve(BudgetKind.GPU,(512<<10)-pipeline);
        BufferBuilder fallback=builder(); seed(); record(renderer,1);
        if (renderer.flush(state,EarlyGlStateTracker.snapshot(),fallback)!=LwjglParticleRenderer.FlushResult.LEGACY_APPENDED
            || renderer.getLastError()!=null || fallback.getVertexCount()!=4)
            throw new AssertionError("slot budget miss must preserve original quad without failure");
        ByteBuffer expected=BufferUtils.createByteBuffer(ParticleVertexEncoder.BYTES_PER_QUAD);
        ParticleVertexEncoder.putQuad(expected,0,0,0,CORNERS,0,0,1,1,1,1,1,1,0,0);
        for(int i=0;i<expected.position();i++) if(expected.get(i)!=fallback.getByteBuffer().get(i))
            throw new AssertionError("legacy byte mismatch at "+i);
        pressure.close();
        byte[] reference=null;
        for(int n: new int[]{1,1,1,1024,1,2048,1,1}) {
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); seed(); record(renderer,n);
            LwjglParticleRenderer.FlushResult result=renderer.flush(state,EarlyGlStateTracker.snapshot(),builder());
            if(result!=LwjglParticleRenderer.FlushResult.MODERN) throw new AssertionError("draw "+n+": "+result+" "+renderer.getLastError());
            ByteBuffer image=BufferUtils.createByteBuffer(32*32*4);
            GL11.glReadPixels(0,0,32,32,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,image);
            byte[] pixels=new byte[image.remaining()]; image.get(pixels);
            if(reference==null) {
                reference=pixels;
                if((pixels[(16*32+16)*4]&255)!=255) throw new AssertionError("blank particle output");
                if(budget.snapshot().getGpuUsed()-pipeline > 16384) throw new AssertionError("small batch reserved maximum-sized VBO");
            } else if(!Arrays.equals(reference,pixels)) throw new AssertionError("growth changed pixels");
            ledger.collect(1,128);
        }
        renderer.close(true); GL11.glFinish(); ledger.collect(1,4096);
        ledger.collect(1,4096); // VAO containers retire before their buffer payloads.
        if(budget.snapshot().getGpuUsed()!=0 || budget.snapshot().getDirectUsed()!=0)
            throw new AssertionError("particle resource leak: "+budget.snapshot().getGpuUsed());
    }

    private static BufferBuilder builder() {
        BufferBuilder b=new BufferBuilder(1024);
        b.begin(GL11.GL_QUADS,DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP); return b;
    }
    private static void record(LwjglParticleRenderer r,int count) {
        for(int i=0;i<count;i++) if(!r.recordQuad(0,0,0,CORNERS,0,0,1,1,1,1,1,1,0,0)) throw new AssertionError("record");
    }
    private static void seed() {
        EarlyGlStateTracker.beginProbe(); EarlyGlStateTracker.useProgram(0);
        EarlyGlStateTracker.bindFramebuffer(GL30.GL_FRAMEBUFFER,0);
        EarlyGlStateTracker.depthFunction(GL11.GL_LESS);
        EarlyGlStateTracker.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER,0);
        EarlyGlStateTracker.clientActiveTexture(GL13.GL_TEXTURE0);
        EarlyGlStateTracker.lightingEnabled(false);
        EarlyGlStateTracker.seedDrawState(0,texture,texture,false,GL11.GL_ONE,GL11.GL_ZERO,
            GL11.GL_ONE,GL11.GL_ZERO,false,true,false,15,1,1,1,1);
        EarlyGlStateTracker.seedHudState(true,true,0,0,32,32);
        int[] textures=new int[32]; textures[0]=texture; textures[1]=texture;
        boolean[] enabled=new boolean[32]; enabled[0]=true; enabled[1]=true;
        EarlyGlStateTracker.seedCompatibilityState(0,true,0,0,2,textures,enabled,
            GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD,GL11.GL_BACK,false,false,0,0,32,32);
    }
}
