package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainUploadContext;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import sun.misc.Unsafe;

/** Hidden native integration test: terrain, models, particles, HUD and FBP share one ledger. */
public final class SharedRenderBudgetNativeProbe {
    private static final int SIZE = 32;
    private static final long PAGE = (16L << 20) - (16L << 20) % 112L;
    private static final double[] CORNERS = { -.5,-.5,0, -.5,.5,0, .5,.5,0, .5,-.5,0 };
    private static int texture;
    private static ParticleState state;
    private static byte[] reference;
    private static final FrameStamp STAMP = new FrameStamp(1,1,new ClientEpochs().snapshot());

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE,SIZE,new PixelFormat().withDepthBits(24),null,null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            texture = GL11.glGenTextures();
            ByteBuffer white = BufferUtils.createByteBuffer(4); white.putInt(-1).flip();
            for(int unit=0;unit<2;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,white);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glLoadIdentity();
            }
            state = new ParticleState(0,texture,texture,0,false,GL11.GL_ONE,GL11.GL_ZERO,
                GL11.GL_ONE,GL11.GL_ZERO,false,true,false,15,false,0);
            run();
            GL11.glDeleteTextures(texture);
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error after cleanup");
            System.out.println("PASS real_terrain_full_budget=true particle_hud_fbp_temporary_fallback=true"
                + " pipeline_and_fences_at_full_budget=true live_slot_reuse=true growth_recovery_without_reset=true"
                + " model_upload_recovery_and_cached_pose_at_full_budget=true"
                + " exact_fallback_bytes_and_pixels=true gpu_direct_zero=true renderer="+GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static void run() throws Exception {
        long prepaid = terrainPrepaidGpuBytes();
        CacheBudget budget = new CacheBudget(16L<<20,64L<<20,PAGE + prepaid);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard,budget,new LwjglResourceDestroyer(),256);
        LwjglTerrainArena terrain = new LwjglTerrainArena(guard,ledger,budget,
            CapabilityReport.builder().build(),PAGE,1);
        require(budget.snapshot().getGpuUsed()==prepaid,"terrain fixed workspace baseline changed");
        LwjglParticleRenderer particles = new LwjglParticleRenderer(guard,ledger,budget,131072);
        LwjglHudRenderer hud = new LwjglHudRenderer(guard,ledger,budget,65536);
        LwjglFbpPacketRenderer fbp = new LwjglFbpPacketRenderer(guard,ledger,16<<20);
        LwjglModelMeshCache models = new LwjglModelMeshCache(guard,ledger,budget);
        hud.prepare(1,1); fbp.prepare(1,1);
        TestChunk chunk = chunk();
        upload(terrain,chunk,(int)(PAGE/112));
        require(terrain.getAllocatedBufferBytes()==PAGE && budget.snapshot().getGpuUsed()==prepaid+PAGE
                && budget.availableBytes(BudgetKind.GPU)==0,
            "real terrain page did not fill payload budget");
        require(particles.initializeGpuResources(1,1),"pipeline blocked by full payload budget");
        require(particles.getLastError()==null,"pipeline error");
        assertFenceAtFullBudget(ledger,budget);
        particle(particles,1,false);
        hud(hud,1,false);
        fbp(fbp,1,false);
        setup();
        require(!models.accept(modelPayload(100),STAMP) && models.lastAdmissionBlockedByCapacity(),
            "model payload miss was not recoverable");
        require(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)==0 && GL11.glGetError()==GL11.GL_NO_ERROR,
            "model payload miss changed GL state");
        release(terrain,chunk,ledger);
        require(budget.snapshot().getGpuUsed()==prepaid,"terrain failed to return unused page");

        // Warm all three real streams, then occupy the remaining storage with
        // terrain. They must still draw and fence when no payload bytes remain.
        for(int i=0;i<3;i++) {
            particle(particles,1,true); hud(hud,1,true); fbp(fbp,1,true);
        }
        require(budget.snapshot().getGpuUsed()-prepaid<32*1024,"small batches reserved configured maxima");
        LwjglModelMeshCache.Entry[] entries = new LwjglModelMeshCache.Entry[8];
        for(int i=0;i<entries.length;i++) {
            setup();
            require(models.accept(modelPayload(100+i),STAMP),"model upload did not recover");
            entries[i]=models.findStateBatchEntry(100+i,STAMP);
            require(entries[i]!=null && entries[i].isValidated(),"model entry invalid");
        }
        models.configurePoseBatching(true);
        model(models,entries,true);
        // Terrain owns a real 8 MiB elastic page while the warmed consumers own
        // their real streams. A further real VBO occupies the exact remainder.
        TestChunk pressureChunk = chunk();
        upload(terrain,pressureChunk,(8<<20)/112);
        require(terrain.getAllocatedBufferBytes()>0,"terrain pressure page missing");
        RenderHandle tail = fillRemainingPayload(ledger,budget);
        require(budget.snapshot().getGpuUsed()==budget.snapshot().getGpuLimit()
                && budget.availableBytes(BudgetKind.GPU)==0,"combined backends did not fill shared budget");
        for(int i=0;i<4;i++) {
            particle(particles,1,true); hud(hud,1,true); fbp(fbp,1,true);
            model(models,entries,true);
        }
        assertFenceAtFullBudget(ledger,budget);
        particle(particles,1024,false); hud(hud,1024,false); fbp(fbp,1024,false);
        if(tail!=null) ledger.retire(tail,LwjglRetirementFence.afterCurrentCommands(ledger));
        release(terrain,pressureChunk,ledger);
        for(int n:new int[]{1024,2048,4096,1}) {
            particle(particles,n,true); hud(hud,n,true); fbp(fbp,n,true);
            ledger.collect(1,256);
        }
        require(terrain.getStrandedRanges()==0,"ordinary pressure stranded a terrain range");
        model(models,entries,false);
        particles.close(true); hud.close(true); fbp.close(true); models.close(true); terrain.close(true);
        GL11.glFinish(); ledger.collect(1,4096); ledger.collect(1,4096); ledger.destroyAll(1);
        require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0,
            "combined consumers leaked resources");
    }

    /** Preserve the original PAGE bytes of cache pressure in addition to fixed execution storage. */
    private static long terrainPrepaidGpuBytes() {
        CacheBudget budget = new CacheBudget(16L<<20,64L<<20,PAGE);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = new ResourceLedger(guard,budget,new LwjglResourceDestroyer(),256);
        LwjglTerrainArena terrain = new LwjglTerrainArena(guard,ledger,budget,
            CapabilityReport.builder().build(),PAGE,1);
        long prepaid = budget.snapshot().getGpuUsed();
        terrain.close(false);
        require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0,
            "uncreated terrain workspace was not released");
        return prepaid;
    }

    private static RenderHandle fillRemainingPayload(ResourceLedger ledger,CacheBudget budget) {
        long bytes=budget.availableBytes(BudgetKind.GPU);
        if(bytes==0)return null;
        CacheBudget.Reservation token=ledger.reserveGpu(bytes);
        require(token!=null,"remainder reservation");
        int buffer=GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,bytes,GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
        return ledger.registerReserved(RenderResourceKind.BUFFER,buffer,bytes,1,1,token);
    }
    private static void assertFenceAtFullBudget(ResourceLedger ledger,CacheBudget budget) {
        long before=budget.snapshot().getGpuUsed();
        require(budget.availableBytes(BudgetKind.GPU)==0,"fence test was not at full shared budget");
        ResourceLedger.RetirementFence fence=LwjglRetirementFence.tryAfterCurrentCommands(ledger);
        require(fence!=null,"real sync denied by payload budget");
        GL11.glFinish(); require(fence.isSignaled(),"real sync did not signal"); fence.destroy();
        require(budget.snapshot().getGpuUsed()==before,"opaque sync changed payload accounting");
    }
    private static void particle(LwjglParticleRenderer renderer,int count,boolean modern) {
        setup();
        for(int i=0;i<count;i++) require(renderer.recordQuad(0,0,0,CORNERS,0,0,1,1,1,1,1,1,0,0),"particle record");
        BufferBuilder fallback=new BufferBuilder(count*ParticleVertexEncoder.BYTES_PER_QUAD);
        fallback.begin(GL11.GL_QUADS,DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        LwjglParticleRenderer.FlushResult result=renderer.flush(state,EarlyGlStateTracker.snapshot(),fallback);
        require(renderer.getLastError()==null,"particle error: "+renderer.getLastError());
        require(result==(modern?LwjglParticleRenderer.FlushResult.MODERN:LwjglParticleRenderer.FlushResult.LEGACY_APPENDED),
            "particle result "+result+" expectedModern="+modern);
        if(modern) assertPixels();
        else {
            require(fallback.getVertexCount()==count*4,"particle fallback lost vertices");
            ByteBuffer expected=BufferUtils.createByteBuffer(ParticleVertexEncoder.BYTES_PER_QUAD);
            ParticleVertexEncoder.putQuad(expected,0,0,0,CORNERS,0,0,1,1,1,1,1,1,0,0);
            for(int n=0;n<count;n++) for(int i=0;i<expected.position();i++)
                require(expected.get(i)==fallback.getByteBuffer().get(n*expected.position()+i),"particle fallback bytes");
        }
    }
    private static void hud(LwjglHudRenderer renderer,int count,boolean modern) {
        setup();
        for(int i=0;i<count;i++) require(renderer.recordQuad(-.5F,-.5F,0,0,0,.5F,.5F,1,1),"HUD record");
        LwjglHudRenderer.FlushResult result=renderer.flush(EarlyGlStateTracker.snapshot());
        require(renderer.getLastError()==null,"HUD error: "+renderer.getLastError());
        require(result==(modern?LwjglHudRenderer.FlushResult.MODERN:LwjglHudRenderer.FlushResult.LEGACY_BUSY),
            "HUD result "+result+" expectedModern="+modern);
        assertPixels();
    }
    private static void fbp(LwjglFbpPacketRenderer renderer,int count,boolean modern) {
        setup(); BufferBuilder packet=blockPacket(count);
        byte[] original=new byte[count*112];
        ByteBuffer view=packet.getByteBuffer().duplicate();view.position(0);view.get(original);
        LwjglFbpPacketRenderer.SubmitResult result=renderer.submitRawPacket(packet,
            EarlyGlStateTracker.snapshot(),EarlyGlStateTracker.compatibilitySnapshot());
        require(renderer.getLastError()==null,"FBP error: "+renderer.getLastError());
        require(result==(modern?LwjglFbpPacketRenderer.SubmitResult.MODERN:LwjglFbpPacketRenderer.SubmitResult.LEGACY_BUSY),
            "FBP result "+result+" expectedModern="+modern);
        if(modern) { require(packet.getVertexCount()==0,"FBP packet not consumed"); assertPixels(); }
        else {
            require(packet.getVertexCount()==count*4,"FBP packet consumed on fallback");
            for(int i=0;i<original.length;i++) require(original[i]==packet.getByteBuffer().get(i),"FBP fallback bytes");
        }
    }
    private static BufferBuilder blockPacket(int count) {
        BufferBuilder builder=new BufferBuilder(count*112);builder.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
        for(int n=0;n<count;n++)for(int i=0;i<4;i++)builder.pos(i==1||i==2?.5:-.5,i>=2?.5:-.5,0)
            .color(255,255,255,255).tex(i==1||i==2?1:0,i>=2?1:0).lightmap(0,0).endVertex();
        return builder;
    }
    private static ModelMeshPayload modelPayload(int id) {
        ByteBuffer bytes=ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        for(int i=0;i<4;i++) {
            bytes.putFloat(i==1||i==2?.5F:-.5F).putFloat(i>=2?.5F:-.5F).putFloat(0);
            bytes.putFloat(i==1||i==2?1:0).putFloat(i>=2?1:0);
            bytes.put((byte)0).put((byte)0).put((byte)127).put((byte)0);
        }
        return new ModelMeshPayload(id,bytes.array(),DefaultVertexFormats.POSITION_TEX_NORMAL,
            GL11.GL_QUADS,4,1,1);
    }
    private static void model(LwjglModelMeshCache renderer,LwjglModelMeshCache.Entry[] entries,boolean pose) {
        setup();
        if(pose) {
            renderer.seedPoseState();
            long before=renderer.getPoseBatchDraws();
            require(renderer.beginStateBatch(entries,entries.length),"model batch declined");
            try { for(LwjglModelMeshCache.Entry entry:entries)
                require(renderer.drawInStateBatch(entry),"model batch part declined");
            } finally { renderer.endStateBatch(); }
            require(renderer.getPoseBatchDraws()>before,"cached pose did not draw: "+renderer.getPoseBatchDetail());
        } else require(renderer.drawCurrent(entries[0],STAMP),"immediate model declined");
        assertPixels();
    }
    private static void assertPixels() {
        ByteBuffer pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        byte[] bytes=new byte[pixels.remaining()];pixels.get(bytes);
        require((bytes[(SIZE/2*SIZE+SIZE/2)*4]&255)==255,"blank output");
        if(reference==null)reference=bytes;else require(Arrays.equals(reference,bytes),"fallback/growth pixels differ");
        require(GL11.glGetError()==GL11.GL_NO_ERROR,"GL draw error");
    }
    private static void setup() {
        GL20.glUseProgram(0); GL30.glBindVertexArray(0); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
        GL11.glViewport(0,0,SIZE,SIZE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
        FloatBuffer identity=BufferUtils.createFloatBuffer(16);
        identity.put(new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}).flip();
        EarlyMatrixStateTracker.seed(GL11.GL_MODELVIEW,identity,identity);
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
    private static void upload(LwjglTerrainArena arena,TestChunk chunk,int quads) {
        BufferBuilder bytes=blockPacket(quads);bytes.finishDrawing();
        TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.compiled);
        FrameStamp stamp=new FrameStamp(1,1,new ClientEpochs().snapshot());
        require(arena.upload(TerrainUploadContext.take(),bytes,chunk.vbo,stamp,false,false,false),"terrain upload");
    }
    private static void release(LwjglTerrainArena arena,TestChunk chunk,ResourceLedger ledger) {
        arena.release(chunk.vbo);arena.endFrame(true);GL11.glFinish();arena.endFrame(true);
        ledger.collect(1,4096);ledger.collect(1,4096);
    }
    private static TestChunk chunk() throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe u=(Unsafe)f.get(null);
        TestChunk chunk=(TestChunk)u.allocateInstance(TestChunk.class);
        chunk.vbo=(TestBuffer)u.allocateInstance(TestBuffer.class);chunk.compiled=new CompiledChunk();return chunk;
    }
    private static void require(boolean value,String detail) { if(!value)throw new AssertionError(detail); }
    private static final class TestChunk extends RenderChunk {
        private TestBuffer vbo; private CompiledChunk compiled;
        private TestChunk(){super(null,null,0);}
        @Override public BlockPos getPosition(){return BlockPos.ORIGIN;}
        @Override public VertexBuffer getVertexBufferByLayer(int layer){return vbo;}
        @Override public CompiledChunk getCompiledChunk(){return compiled;}
    }
    private static final class TestBuffer extends VertexBuffer implements ChunkVertexBufferAccessor {
        private TestBuffer(){super(DefaultVertexFormats.BLOCK);}
        public int ice$glBufferId(){return 0;} public int ice$vertexStrideBytes(){return 28;}
        public int ice$vertexCount(){return 0;}
        public int ice$capacityBytes(){return 0;} public void ice$setCapacityBytes(int bytes){}
        public void ice$setVertexCount(int count){}
    }
}
