package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyModelLightingState;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

/** Hidden real-GL original-order terrain submission regression. */
public final class TerrainOrderedRangesNativeProbe {
    private static final int SIZE = 64;
    private static final long MIB = 1024L * 1024L;
    private static final long PAGE = 16L * MIB - (16L * MIB) % 112L;
    private static final FrameStamp STAMP = new FrameStamp(1L, 1L, new ClientEpochs().snapshot());
    private static long frame;

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            if (args.length > 0 && "standard".equals(args[0])) {
                Field capability = ContextCapabilities.class.getField("GL_NV_bindless_multi_draw_indirect");
                capability.setAccessible(true); capability.setBoolean(GLContext.getCapabilities(), false);
            }
            for (boolean persistent : new boolean[] {false, true}) trace(persistent);
            sharedRegionTransforms();
            whollyExternal();
            System.out.println("terrain_submission_native=true renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } catch (Throwable failure) { failure.printStackTrace(System.out); throw failure; } finally { context.destroy(); }
    }

    private static void trace(boolean persistent) throws Exception {
        CacheBudget budget = new CacheBudget(8L * MIB, 8L * MIB, 40L * MIB);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard, budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT)
                .pass(ModernCapability.PERSISTENT_MAPPING).build(), 32L * MIB, 1L);
        try {
            arena.configureNativeBatching(true, false);
            GpuArenaAllocator allocator = (GpuArenaAllocator) field(arena, "allocator");
            List<ArenaRange> retained = new ArrayList<ArenaRange>();
            ArenaRange aHole = allocator.allocate(448L);
            retained.add(allocator.allocate(112L));
            ArenaRange aSmall = allocator.allocate(224L);
            TestChunk a0 = chunk(), a1 = chunk(), b0 = chunk(), b1 = chunk();
            upload(arena, a0, builder(-.9F, .6F, 255, 0, 0, 1), persistent);
            upload(arena, a1, builder(-.6F, .9F, 0, 255, 0, 1), persistent);
            retained.add(allocator.allocate(PAGE - 1008L));
            ArenaRange bSmall = allocator.allocate(112L);
            retained.add(allocator.allocate(112L));
            ArenaRange bHole = allocator.allocate(448L);
            upload(arena, b0, builder(-.8F, .7F, 0, 0, 255, 1), persistent);
            upload(arena, b1, builder(-.7F, .8F, 255, 255, 0, 1), persistent);
            retained.add(allocator.allocate(PAGE - 896L));
            Container spatial = new Container(a0, a1, b0, b1);
            compare(arena, spatial, "initial", persistent);
            // Queue the old native geometry and update without a finish/wait.
            setupBlend(); arena.configureNativeBatching(true, false);
            require(arena.render(spatial.copy(), BlockRenderLayer.SOLID, true, true, ++frame), "queued old draw");
            allocator.free(aHole); allocator.free(aSmall); allocator.free(bSmall); allocator.free(bHole);
            Object previousRecord=((Map)field(arena,"meshes")).get(a1.vbo);
            Object previousRange=field(previousRecord,"range");
            long previousReuse=arena.getExactSizeReplacementUploads();
            upload(arena, a1, builder(-.6F, .9F, 0, 200, 100, 1), persistent);
            Object replacedRecord=((Map)field(arena,"meshes")).get(a1.vbo);
            require(field(replacedRecord,"range")==previousRange,"equal-size rebuild moved its allocation");
            require(arena.getExactSizeReplacementUploads()==previousReuse+1,"equal-size reuse was not recorded");
            upload(arena, b0, builder(-.8F, .7F, 100, 0, 200, 2), persistent);
            byte[] queued = pixels();
            require(Arrays.equals(queued, initialPixels), "replacement changed already queued old draw");
            compare(arena, spatial, "spatial-rebuild", persistent);
            compare(arena, new Container(a0, b0, a1, b1), "alternating-view", persistent);
            mixedPlans(arena, a0, a1, b0, b1);
            System.out.println("layout persistent=" + persistent + " pages=" + page(arena,a0) + ","
                + page(arena,a1) + "," + page(arena,b0) + "," + page(arena,b1)
                + " free_segments=" + allocator.snapshot().getFreeSegments()
                + " physical=" + arena.getAllocatedBufferBytes()
                + " rejected=" + arena.getRejectedUploads());
            for (TestChunk chunk : new TestChunk[] {a0,a1,b0,b1}) arena.release(chunk.vbo);
            arena.endFrame(true); GL11.glFinish(); arena.endFrame(true);
            for (ArenaRange range : retained) require(allocator.free(range), "filler free");
            require(allocator.snapshot().getUsedBytes() == 0L, "ranges leaked");
        } finally { arena.close(true); GL11.glFinish(); ledger.destroyAll(1L); }
        require(budget.snapshot().getGpuUsed() == 0L && budget.snapshot().getDirectUsed() == 0L
            && budget.snapshot().getHeapUsed() == 0L, "budget leaked");
    }

    private static void mixedPlans(LwjglTerrainArena arena, TestChunk... nativeChunks) throws Exception {
        TestChunk legacy = chunk();
        legacy.vbo.id = GL15.glGenBuffers(); legacy.vbo.count = 4;
        BufferBuilder source = builder(-.3F,.9F,80,150,220,1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,legacy.vbo.id);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,source.getByteBuffer(),GL15.GL_STATIC_DRAW);
        java.util.Random random = new java.util.Random(101020L);
        try {
            for (int test=0;test<64;test++) {
                int count = test==63 ? 1024 : test==62 ? 3 : 4+random.nextInt(25);
                RenderChunk[] chunks = new RenderChunk[count]; chunks[0]=nativeChunks[0];
                long expectedDraws=1; int previousPage=page(arena,nativeChunks[0]);
                for(int i=1;i<count;i++) {
                    int chosen=random.nextInt(5);
                    if(chosen==4) {
                        chunks[i]=legacy;
                        if(previousPage!=-1) expectedDraws++;
                        previousPage=-1;
                    }
                    else {
                        chunks[i]=nativeChunks[chosen]; int page=page(arena,nativeChunks[chosen]);
                        if(page!=previousPage) expectedDraws++;
                        previousPage=page;
                    }
                }
                Container list=new Container(chunks);
                setupBlend(); arena.configureNativeBatching(false,false);
                require(arena.render(list.copy(),BlockRenderLayer.SOLID,true,false,++frame),"mixed reference");
                byte[] reference=pixels();
                setupBlend(); arena.configureNativeBatching(true,false);
                long before=arena.getShaderBatchDrawCalls();
                long beforeCommands=arena.getShaderBatchCommands();
                require(arena.render(list.copy(),BlockRenderLayer.SOLID,true,true,++frame),"mixed native");
                require(Arrays.equals(reference,pixels()),"mixed original-order pixels " + test);
            }
            require(GL11.glGetError()==GL11.GL_NO_ERROR,"mixed GL error");
            legacy.position = new BlockPos(160, 64, -32);
            for (int view = 0; view < 24; view++) {
                Container positioned = new Container(nativeChunks[0], legacy, nativeChunks[1], legacy);
                positioned.viewX = 160.25 + view * .01;
                positioned.viewY = 64.1;
                positioned.viewZ = -31.8;
                setupBlend(); GL11.glRotatef(view * 3.0F, 0, 0, 1);
                arena.configureNativeBatching(false, false);
                require(arena.render(positioned.copy(),BlockRenderLayer.SOLID,true,false,++frame),"translated reference");
                byte[] reference = pixels();
                setupBlend(); GL11.glRotatef(view * 3.0F, 0, 0, 1);
                arena.configureNativeBatching(true, false);
                require(arena.render(positioned.copy(),BlockRenderLayer.SOLID,true,true,++frame),"translated hybrid");
                require(Arrays.equals(reference,pixels()),"translated/rotated view " + view);
            }
            legacy.position = BlockPos.ORIGIN;
            arena.beforeBufferStorageReplacement(legacy.vbo);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, legacy.vbo.id);
            BufferBuilder replacement = builder(-.7F,.1F,170,10,250,2);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,replacement.getByteBuffer(),GL15.GL_STATIC_DRAW);
            legacy.vbo.count = 8;
            compare(arena, new Container(nativeChunks[0], legacy, nativeChunks[1]), "external-storage-replaced", false);
            // The original owner deletes/recreates its GL name. Reusing the Java
            // VertexBuffer must never leave its old captured VAO attached.
            arena.release(legacy.vbo);
            GL15.glDeleteBuffers(legacy.vbo.id);
            legacy.vbo.id = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,legacy.vbo.id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,replacement.getByteBuffer(),GL15.GL_STATIC_DRAW);
            compare(arena, new Container(nativeChunks[0], legacy, nativeChunks[1]), "external-name-recreated", false);
            System.out.println("mixed_plans=64 exact_ordered_pixels=true fixed_ranges=true cross_page=" + false
                + " stream_growth=true camera_views=24 external_replacement=true external_name_lifecycle=true");
        } finally { arena.release(legacy.vbo); GL15.glDeleteBuffers(legacy.vbo.id); }
    }
    private static boolean crossPage() {
        ContextCapabilities c = GLContext.getCapabilities();
        return c.GL_NV_bindless_multi_draw_indirect && c.GL_NV_vertex_buffer_unified_memory && c.GL_NV_shader_buffer_load;
    }

    /** Exact vanilla chunks enter the shared region scope; subclass and unowned
     * chunks must end it. The old unbatched path supplies the pixel reference. */
    private static void sharedRegionTransforms() throws Exception {
        CacheBudget budget=new CacheBudget(8L*MIB,8L*MIB,40L*MIB);
        RenderThreadGuard guard=RenderThreadGuard.captureCurrent();
        ResourceLedger ledger=ledger(guard,budget);
        LwjglTerrainArena arena=new LwjglTerrainArena(guard,ledger,budget,
            CapabilityReport.builder().build(),32L*MIB,1L);
        GpuArenaAllocator allocator=(GpuArenaAllocator)field(arena,"allocator");
        TestChunk foreign=chunk(), legacy=chunk();
        try {
            RenderChunk a=vanillaChunk(0), b=vanillaChunk(0), c=vanillaChunk(256);
            uploadVanilla(arena,a,255,0,0);
            ArenaRange filler=allocator.allocate(PAGE-112);
            uploadVanilla(arena,b,0,255,0);
            uploadVanilla(arena,c,0,0,255);
            upload(arena,foreign,builder(-.5F,.8F,220,100,40,1),false);
            legacy.vbo.id=GL15.glGenBuffers();legacy.vbo.count=4;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,legacy.vbo.id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,builder(-.3F,.9F,80,150,220,1).getByteBuffer(),GL15.GL_STATIC_DRAW);
            RenderChunk[][] plans={
                {a,b,a,b}, {a,b,foreign,a,b}, {a,b,legacy,a,b},
                {a,b,c,c,a,b}, {a,b,foreign,legacy,c,c,b,a}
            };
            for(int view=0;view<24;view++) for(RenderChunk[] plan:plans) {
                Container list=new Container(plan);list.viewX=view%2==0?.125:256.125;
                list.viewY=.1;list.viewZ=-.2;
                setupBlend();GL11.glRotatef(view*3,0,0,1);
                arena.configureNativeBatching(false,false);
                require(arena.render(list.copy(),BlockRenderLayer.SOLID,false,false,++frame),"region reference");
                byte[] reference=pixels();
                setupBlend();GL11.glRotatef(view*3,0,0,1);
                java.nio.FloatBuffer before=BufferUtils.createFloatBuffer(16),after=BufferUtils.createFloatBuffer(16);
                GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,before);
                int depth=GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
                arena.configureNativeBatching(true,false);
                require(arena.render(list.copy(),BlockRenderLayer.SOLID,true,true,++frame),"shared region draw");
                GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,after);
                require(before.equals(after),"shared region changed caller matrix");
                require(depth==GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH),"shared region matrix stack leaked");
                require(Arrays.equals(reference,pixels()),"shared region order/view pixels "+view);
            }
            for(RenderChunk chunk:new RenderChunk[]{a,b,c})arena.release(chunk.getVertexBufferByLayer(0));
            arena.release(foreign.vbo);
            arena.endFrame(true);GL11.glFinish();arena.endFrame(true);
            require(allocator.free(filler),"region filler free");
            require(GL11.glGetError()==0,"shared region GL error");
            System.out.println("shared_region_transforms=true exact_pixels=120 cross_page=true subclass_barrier=true legacy_barrier=true matrix_restored=true");
        } finally {
            if(legacy.vbo.id!=0)GL15.glDeleteBuffers(legacy.vbo.id);
            arena.close(true);GL11.glFinish();ledger.destroyAll(1);
        }
        require(budget.snapshot().getGpuUsed()==0&&budget.snapshot().getDirectUsed()==0
            &&budget.snapshot().getHeapUsed()==0,"shared region budget leak");
    }
    static RenderChunk vanillaChunk(int x)throws Exception {
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Unsafe unsafe=(Unsafe)singleton.get(null);
        RenderChunk chunk=(RenderChunk)unsafe.allocateInstance(RenderChunk.class);
        TestBuffer buffer=(TestBuffer)unsafe.allocateInstance(TestBuffer.class);
        setVanilla(chunk,"position",new BlockPos.MutableBlockPos(x,0,0));
        setVanilla(chunk,"vertexBuffers",new VertexBuffer[]{buffer,buffer,buffer,buffer});
        setVanilla(chunk,"compiledChunk",new CompiledChunk());
        return chunk;
    }
    private static void setVanilla(RenderChunk chunk,String name,Object value)throws Exception {
        Field field=RenderChunk.class.getDeclaredField(name);field.setAccessible(true);field.set(chunk,value);
    }
    static void uploadVanilla(LwjglTerrainArena arena,RenderChunk chunk,int r,int g,int b) {
        int x=chunk.getPosition().getX();BufferBuilder builder=new BufferBuilder(112);
        builder.setTranslation(-x,0,0);builder.begin(GL11.GL_QUADS,DefaultVertexFormats.BLOCK);
        for(int i=0;i<4;i++)builder.pos(x+(i==1||i==2?.8:-.8),i>=2?.8:-.8,0)
            .color(r,g,b,128).tex(0,0).lightmap(0,0).endVertex();
        builder.finishDrawing();TerrainUploadContext.begin(BlockRenderLayer.SOLID,chunk,chunk.getCompiledChunk());
        require(arena.upload(TerrainUploadContext.take(),builder,chunk.getVertexBufferByLayer(0),STAMP,false,false,false),"vanilla upload");
    }

    /** Full native rendering is also useful when the cache owns no mesh at all. */
    private static void whollyExternal() throws Exception {
        CacheBudget budget = new CacheBudget(8L*MIB, 8L*MIB, 16L*MIB);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard,budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard,ledger,budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT).build(),16L*MIB,1L);
        TestChunk[] values = {chunk(),chunk(),chunk(),chunk()};
        try {
            for(int i=0;i<values.length;i++) {
                values[i].vbo.id=GL15.glGenBuffers(); values[i].vbo.count=4;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,values[i].vbo.id);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER,builder(-.9F+i*.15F,.5F+i*.12F,220-i*50,30+i*60,180-i*30,1).getByteBuffer(),GL15.GL_STATIC_DRAW);
            }
            arena.configureNativeBatching(true,false);
            arena.configureVisibility(STAMP,null,null);
            for(int replacement=0;replacement<2;replacement++) {
                RenderChunk[] sequence=new RenderChunk[64];
                for(int i=0;i<sequence.length;i++) sequence[i]=values[i%values.length];
                Container list=new Container(sequence);
                require(!arena.ownsAny(list,BlockRenderLayer.SOLID),"external test unexpectedly owns geometry");
                setupBlend();
                for(RenderChunk chunk:sequence) {
                    GL11.glPushMatrix(); chunk.multModelviewMatrix();
                    VertexBuffer buffer=chunk.getVertexBufferByLayer(0); buffer.bindBuffer();
                    GL11.glVertexPointer(3,GL11.GL_FLOAT,28,0L);
                    GL11.glColorPointer(4,GL11.GL_UNSIGNED_BYTE,28,12L);
                    GL13.glClientActiveTexture(GL13.GL_TEXTURE0); GL11.glTexCoordPointer(2,GL11.GL_FLOAT,28,16L);
                    GL13.glClientActiveTexture(GL13.GL_TEXTURE1); GL11.glTexCoordPointer(2,GL11.GL_SHORT,28,24L);
                    GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
                    buffer.drawArrays(GL11.GL_QUADS); GL11.glPopMatrix();
                }
                byte[] expected=pixels(); setupBlend();
                byte[] untouched=pixels();
                Container pending=list.copy();
                require(!arena.render(pending,BlockRenderLayer.SOLID,true,true,++frame),"unowned list must use original renderer");
                require(pending.ice$renderChunks().size()==64,"unowned list consumed");
                require(Arrays.equals(untouched,pixels()),"declined layer touched framebuffer");
                if(replacement==0) {
                    arena.beforeBufferStorageReplacement(values[2].vbo);
                    if(crossPage()) require(!NVShaderBufferLoad.glIsNamedBufferResidentNV(values[2].vbo.id),"direct upload residency");
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,values[2].vbo.id);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER,builder(-.7F,.7F,1,240,170,2).getByteBuffer(),GL15.GL_STATIC_DRAW);
                    values[2].vbo.count=8;
                }
            }
            require(GL11.glGetError()==0,"wholly external GL error");
            System.out.println("wholly_external_declined=true arena_owned_meshes=0 list_untouched=true unused_legacy_count="
                +(crossPage()?1:64)+" direct_storage_replacement=true");
        } finally {
            for(TestChunk chunk:values) if(chunk.vbo.id!=0) { arena.release(chunk.vbo); GL15.glDeleteBuffers(chunk.vbo.id); }
            arena.close(true); GL11.glFinish(); ledger.destroyAll(1L);
        }
        require(budget.snapshot().getGpuUsed()==0 && budget.snapshot().getDirectUsed()==0
            && budget.snapshot().getHeapUsed()==0,"wholly external budget leak");
    }
    private static byte[] initialPixels;
    private static void compare(LwjglTerrainArena arena, Container chunks, String trace, boolean persistent) {
        setupBlend(); arena.configureNativeBatching(false, false);
        require(arena.render(chunks.copy(), BlockRenderLayer.SOLID, true, false, ++frame), "reference render");
        byte[] reference = pixels();
        setupBlend(); arena.configureNativeBatching(true, false);
        long before = arena.getShaderBatchDrawCalls();
        require(arena.render(chunks.copy(), BlockRenderLayer.SOLID, true, true, ++frame), "native render");
        byte[] actual = pixels();
        require(Arrays.equals(reference, actual), "ordered native pixels differ");
        if (trace.equals("initial")) initialPixels = actual;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(actual);
        System.out.println("trace=" + trace + " persistent=" + persistent + " draws="
            + (arena.getShaderBatchDrawCalls() - before) + " pixels_crc=" + crc.getValue());
    }
    private static int page(LwjglTerrainArena arena, TestChunk chunk) throws Exception {
        java.util.Map<?,?> meshes = (java.util.Map<?,?>) field(arena,"meshes");
        return (int) (((ArenaRange) field(meshes.get(chunk.vbo),"range")).getOffset() / PAGE);
    }
    static byte[] pixels() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0,0,SIZE,SIZE,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,bytes);
        byte[] result = new byte[bytes.capacity()]; bytes.get(result); return result;
    }
    static void setupBlend() {
        setup(); GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }
    static ResourceLedger ledger(RenderThreadGuard guard, CacheBudget budget) {
        return new ResourceLedger(guard, budget, (kind, id) -> {
            if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
            else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
            else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
        }, 128);
    }

    private static void upload(LwjglTerrainArena arena, TestChunk chunk, BufferBuilder builder,
                               boolean persistent) {
        require(uploadResult(arena, chunk, builder, persistent), "upload rejected");
    }
    private static boolean uploadResult(LwjglTerrainArena arena, TestChunk chunk, BufferBuilder builder,
                                         boolean persistent) {
        int bytes = builder.getVertexCount() * 28;
        ByteBuffer expected = ByteBuffer.allocate(bytes);
        LwjglTerrainArena.normalizeIntoStaging(builder.getByteBuffer(), bytes, 28, bytes,
            BlockPos.ORIGIN, 0, 0, 0, expected);
        TerrainUploadContext.begin(BlockRenderLayer.SOLID, chunk, chunk.compiled);
        boolean uploaded = arena.upload(TerrainUploadContext.take(), builder, chunk.vbo, STAMP,
            persistent, persistent, false);
        if (uploaded) chunk.expected = expected.array();
        return uploaded;
    }
    static BufferBuilder builder(float x0, float x1, int r, int g, int b, int quads) {
        BufferBuilder builder = new BufferBuilder(quads * 112);
        builder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        for (int q = 0; q < quads; q++) for (int i = 0; i < 4; i++) {
            builder.pos(i == 1 || i == 2 ? x1 : x0, i >= 2 ? .8 : -.8, 0)
                .color(r, g, b, 128).tex(0, 0).lightmap(0, 0).endVertex();
        }
        builder.finishDrawing();
        return builder;
    }
    static void setup() {
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_LIGHTING); GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL20.glUseProgram(0); GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        for (int i = 1; i >= 0; i--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i); GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glClearColor(0,0,0,0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        EarlyGlStateTracker.invalidate();
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        EarlyGlStateTracker.bindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        EarlyGlStateTracker.useProgram(0); EarlyGlStateTracker.bindVertexArray(0);
        EarlyModelLightingState.seed(new int[EarlyModelLightingState.SIZE], new int[8], 0);
    }
    private static void assertRgb(float x, int r, int g, int b) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels((int) ((x + 1F) * .5F * SIZE), SIZE / 2, 1, 1,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        require((pixel.get(0) & 255) == r && (pixel.get(1) & 255) == g
            && (pixel.get(2) & 255) == b, "unexpected pixel at " + x + ": "
                + (pixel.get(0) & 255) + "," + (pixel.get(1) & 255) + "," + (pixel.get(2) & 255));
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(object);
    }
    static TestChunk chunk() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        TestChunk chunk = (TestChunk) unsafe.allocateInstance(TestChunk.class);
        chunk.vbo = (TestBuffer) unsafe.allocateInstance(TestBuffer.class);
        chunk.compiled = new CompiledChunk();
        return chunk;
    }
    static void require(boolean valid, String detail) {
        if (!valid) throw new AssertionError(detail);
    }
    static final class TestChunk extends RenderChunk {
        TestBuffer vbo;
        CompiledChunk compiled;
        private byte[] expected;
        BlockPos position;
        private TestChunk() { super(null, null, 0); }
        @Override public BlockPos getPosition() { return position == null ? BlockPos.ORIGIN : position; }
        @Override public void multModelviewMatrix() {
            GL11.glTranslatef(8.0F * 1.000001F - 8.0F, 8.0F * 1.000001F - 8.0F,
                8.0F * 1.000001F - 8.0F);
            GL11.glScalef(1.000001F, 1.000001F, 1.000001F);
        }
        @Override public VertexBuffer getVertexBufferByLayer(int layer) { return vbo; }
        @Override public CompiledChunk getCompiledChunk() { return compiled; }
    }
    static final class TestBuffer extends VertexBuffer implements ChunkVertexBufferAccessor {
        int id, count;
        private TestBuffer() { super(DefaultVertexFormats.BLOCK); }
        @Override public void bindBuffer() { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id); }
        @Override public void drawArrays(int mode) { GL11.glDrawArrays(mode,0,count); }
        public int ice$glBufferId() { return id; }
        public int ice$vertexCount() { return count; }
        public int ice$vertexStrideBytes() { return 28; }
        public int ice$capacityBytes() { return 0; }
        public void ice$setCapacityBytes(int bytes) { }
        public void ice$setVertexCount(int vertices) { }
    }
    static final class Container implements TerrainRenderListAccessor {
        private final List<RenderChunk> chunks = new ArrayList<RenderChunk>();
        Container(RenderChunk... chunks) { this.chunks.addAll(Arrays.asList(chunks)); }
        double viewX, viewY, viewZ;
        Container copy() {
            Container copy = new Container(chunks.toArray(new RenderChunk[0]));
            copy.viewX = viewX; copy.viewY = viewY; copy.viewZ = viewZ;
            return copy;
        }
        public List<RenderChunk> ice$renderChunks() { return chunks; }
        public boolean ice$initialized() { return true; }
        public double ice$viewEntityX() { return viewX; }
        public double ice$viewEntityY() { return viewY; }
        public double ice$viewEntityZ() { return viewZ; }
    }
}
