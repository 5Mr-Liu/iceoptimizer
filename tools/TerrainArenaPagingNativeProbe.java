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

/** Hidden real-GL regression probe of production arena uploads and ordered draws. */
public final class TerrainArenaPagingNativeProbe {
    private static final int SIZE = 64;
    private static final long MIB = 1024L * 1024L;
    private static final long PAGE = 16L * MIB - (16L * MIB) % 112L;
    private static final FrameStamp STAMP = new FrameStamp(1L, 1L, new ClientEpochs().snapshot());

    public static void main(String[] args) throws Exception {
        Pbuffer context = new Pbuffer(SIZE, SIZE, new PixelFormat().withDepthBits(24), null, null);
        try {
            context.makeCurrent();
            OpenGlHelper.defaultTexUnit = GL13.GL_TEXTURE0;
            OpenGlHelper.lightmapTexUnit = GL13.GL_TEXTURE1;
            if (args.length > 0 && "standard".equals(args[0])) {
                Field feature = ContextCapabilities.class.getField("GL_NV_bindless_multi_draw_indirect");
                feature.setAccessible(true); feature.setBoolean(GLContext.getCapabilities(), false);
            }
            for (boolean persistent : new boolean[] {false, true}) {
                if (persistent && !GLContext.getCapabilities().GL_ARB_buffer_storage)
                    throw new AssertionError("persistent buffer storage unavailable");
                pagingAndReplacement(persistent);
                elasticAndFullBudgetRecovery(persistent);
                capacityFitsThirteenFreeMiB(persistent);
            }
            lazyBeyond128();
            System.out.println("terrain_paging_native=true ordered_subdata_replacement=true"
                + " persistent_replacement=true local_first_vertices=true budget_on_demand=true"
                + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally { context.destroy(); }
    }

    private static ResourceLedger ledger(RenderThreadGuard guard, CacheBudget budget) {
        return new ResourceLedger(guard, budget, (kind, id) -> {
            if (kind == RenderResourceKind.BUFFER) GL15.glDeleteBuffers(id);
            else if (kind == RenderResourceKind.VERTEX_ARRAY) GL30.glDeleteVertexArrays(id);
            else if (kind == RenderResourceKind.PROGRAM) GL20.glDeleteProgram(id);
        }, 128);
    }

    private static void pagingAndReplacement(boolean persistent) throws Exception {
        CacheBudget budget = new CacheBudget(8L * MIB, 8L * MIB, 40L * MIB);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard, budget);
        CapabilityReport capabilities = CapabilityReport.builder()
            .pass(ModernCapability.MULTI_DRAW_INDIRECT)
            .pass(ModernCapability.PERSISTENT_MAPPING).build();
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget, capabilities,
            32L * MIB, 1L);
        long prepaid = budget.snapshot().getGpuUsed();
        try {
            GpuArenaAllocator allocator = (GpuArenaAllocator) field(arena, "allocator");
            require(arena.getAllocatedBufferBytes() == 0, "constructor allocated terrain pages");
            ArenaRange firstFiller = allocator.allocate(PAGE - 224L);
            TestChunk left = chunk(), right = chunk();
            upload(arena, left, builder(-.9F, -.1F, 255, 0, 0, 2), persistent);
            require(budget.snapshot().getGpuUsed() - prepaid == PAGE, "first upload must commit exactly one page");
            upload(arena, right, builder(.1F, .9F, 0, 255, 0, 1), persistent);
            require(budget.snapshot().getGpuUsed() - prepaid == PAGE + 256L * 1024L,
                "small second page should not reserve its full logical extent");
            require(arena.wasLastUploadPersistent() == persistent, "unexpected upload path");
            ArenaRange secondFiller = allocator.allocate(PAGE - 112L);
            require(allocator.snapshot().getUsedBytes() == PAGE * 2L, "test arena not full");

            for (boolean nativeBatch : new boolean[] {false, true}) {
                setup();
                arena.configureNativeBatching(nativeBatch, false);
                require(arena.render(new Container(left, right), BlockRenderLayer.SOLID,
                    true, nativeBatch, nativeBatch ? 2L : 1L), "paged layer not rendered");
                assertRgb(-.5F, 255, 0, 0);
                assertRgb(.5F, 0, 255, 0);
            }
            require(arena.getShaderBatchDrawCalls() == (crossPage() ? 1L : 2L),
                "native cross-page shader batching silently fell back");
            long workingGpu = budget.snapshot().getGpuUsed() - arena.getAllocatedBufferBytes();

            setup();
            arena.configureNativeBatching(false, false);
            require(arena.render(new Container(left), BlockRenderLayer.SOLID, true, false, 3L), "old draw");
            GLSync oldDraw = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            // No wait or glFinish between the old draw and its storage update.
            // The old left-hand pixels must survive; the new right-hand draw
            // sees the replacement in the exact same full-arena slot.
            upload(arena, left, builder(.1F, .9F, 0, 0, 255, 1), persistent);
            require(!arena.wasLastUploadPersistent(), "reused slot raced GPU with CPU mapped write");
            require(allocator.snapshot().getUsedBytes() == PAGE * 2L, "replacement consumed extra storage");
            require(arena.getOwnedMeshes() == 2, "replacement lost mesh ownership");
            require(arena.render(new Container(left), BlockRenderLayer.SOLID, true, false, 4L), "new draw");
            assertRgb(-.5F, 255, 0, 0);
            assertRgb(.5F, 0, 0, 255);
            GL32.glDeleteSync(oldDraw);
            assertReadback(arena, left, "shrunken slot validation included stale tail bytes");
            arena.release(left.vbo);
            arena.endFrame(true);
            GL11.glFinish(); // Test cleanup only; production remains nonblocking.
            arena.endFrame(true);
            require(allocator.snapshot().getUsedBytes() == PAGE * 2L - 224L,
                "replacement slot retired twice or failed to reclaim");
            allocator.free(firstFiller); allocator.free(secondFiller);
            Object batching = field(arena, "nativeBatches");
            List<?> layouts = (List<?>) field(batching, "pages");
            require(layouts.size() == 2, "missing fixed page layouts");
            Object[] retiredLayouts = layouts.toArray();
            int[] retiredVaos = new int[layouts.size()];
            int[] retiredBuffers = new int[layouts.size()];
            for (int i = 0; i < layouts.size(); i++) {
                RenderHandle vao = (RenderHandle) field(retiredLayouts[i], "vao");
                require(crossPage() || vao != null, "standard page has no captured layout");
                retiredVaos[i] = vao == null ? 0 : vao.getNativeId();
                retiredBuffers[i] = (Integer) field(retiredLayouts[i], "terrainBuffer");
                require(GL15.glIsBuffer(retiredBuffers[i]), "terrain page was not live before retirement");
                if (crossPage()) require((Long) field(retiredLayouts[i], "gpuAddress") != 0L
                    && NVShaderBufferLoad.glIsNamedBufferResidentNV(retiredBuffers[i]), "page is not resident before release");
            }
            arena.release(right.vbo);
            arena.endFrame(true); GL11.glFinish(); arena.endFrame(true);
            require(arena.getAllocatedBufferBytes() == 0L, "fully free pages not retired");
            require(layouts.isEmpty(), "empty pages retained their VAO layouts");
            GL11.glFinish(); ledger.collect(1L, 128);
            ledger.collect(1L, 128); // Containers retire before their referenced payloads.
            for (int i = 0; i < retiredVaos.length; i++) {
                if (retiredVaos[i] != 0) require(!GL30.glIsVertexArray(retiredVaos[i]),
                    "retired page layout remained live");
                require((Long) field(retiredLayouts[i], "gpuAddress") == 0L, "released page retained GPU address");
                require(!GL15.glIsBuffer(retiredBuffers[i]), "retired terrain storage remained live");
            }
            require(budget.snapshot().getGpuUsed() == workingGpu, "empty pages did not return their exact GPU budget");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "native GL error");
            System.out.println("persistent=" + persistent + " pages=2 replacement_bytes=112"
                + " retained_slot_bytes=224 cross_page_pixels=exact queued_draw_pixels=exact");
        } finally {
            arena.close(true);
            GL11.glFinish();
            ledger.destroyAll(1L);
            require(budget.snapshot().getGpuUsed() == 0L, "GPU budget leaked after arena close");
        }
    }

    private static void lazyBeyond128() throws Exception {
        CacheBudget budget = new CacheBudget(8L * MIB, 8L * MIB, 20L * MIB);
        RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
        ResourceLedger ledger = ledger(guard, budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(guard, ledger, budget,
            CapabilityReport.builder().build(), 256L * MIB, 1L);
        long prepaid = budget.snapshot().getGpuUsed();
        try {
            GpuArenaAllocator allocator = (GpuArenaAllocator) field(arena, "allocator");
            for (int i = 0; i < 9; i++) allocator.allocate(PAGE);
            TestChunk chunk = chunk();
            upload(arena, chunk, builder(-.5F, .5F, 255, 255, 255, 1), false);
            require(allocator.snapshot().getUsedBytes() > 128L * MIB, "old hard limit remains");
            require(budget.snapshot().getGpuUsed() - prepaid == 256L * 1024L, "virtual holes allocated physical pages");
            assertReadback(arena, chunk, "page beyond 128 MiB read wrong offset");
            // A second physical page is denied by the shared budget even though
            // the terrain's virtual maximum would permit it.
            ArenaRange filler = allocator.allocate(PAGE - 112L);
            CacheBudget.Reservation blocker = budget.tryReserve(
                dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU,
                20L * MIB - budget.snapshot().getGpuUsed());
            TestChunk rejected = chunk();
            require(!uploadResult(arena, rejected, builder(-.5F, .5F, 1, 2, 3, 1), false),
                "terrain exceeded total GPU budget");
            require(budget.snapshot().getGpuUsed() == 20L * MIB, "rejected page leaked reservation");
            blocker.close();
            allocator.free(filler);
            arena.release(chunk.vbo);
            arena.endFrame(true); GL11.glFinish(); arena.endFrame(true);
            require(arena.getAllocatedBufferBytes() == 0L, "empty terrain page retained storage");
            GL11.glFinish(); ledger.collect(1L, 128);
            require(budget.snapshot().getGpuUsed() == prepaid, "empty page budget not returned to other modules");
            long available = budget.availableBytes(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU);
            require(available == 20L * MIB - prepaid, "released page space is not available");
            CacheBudget.Reservation other = budget.tryReserve(
                dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU, available);
            require(other != null,
                "other module cannot reuse terrain's released GPU budget");
            other.close();
            require(budget.snapshot().getGpuUsed() == prepaid, "peer release changed workspace reservation");
        } finally {
            arena.close(true); GL11.glFinish(); ledger.destroyAll(1L);
            require(budget.snapshot().getGpuUsed() == 0L, "lazy page budget leaked after close");
        }
    }

    private static void elasticAndFullBudgetRecovery(boolean persistent) throws Exception {
        long limit = 4L * MIB;
        CacheBudget budget = new CacheBudget(8L * MIB, 8L * MIB, limit);
        ResourceLedger ledger = ledger(RenderThreadGuard.captureCurrent(), budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(RenderThreadGuard.captureCurrent(), ledger, budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT)
                .pass(ModernCapability.PERSISTENT_MAPPING).build(), 32L * MIB, 1L);
        final int[] pressureCallbacks = {0};
        ledger.setGpuReclaimer(() -> {
            pressureCallbacks[0]++;
            arena.reclaimUnusedPages(1L);
        });
        CacheBudget.Reservation blocker = null;
        int copyBinding = GL15.glGenBuffers();
        try {
            TestChunk left = chunk(), right = chunk(), extra = chunk();
            upload(arena, left, builder(-.9F, -.1F, 255, 0, 0, 1), persistent);
            require(arena.getAllocatedBufferBytes() == 256L * 1024L, "tiny upload allocated 16MiB");
            setup(); arena.configureNativeBatching(true, false);
            require(arena.render(new Container(left), BlockRenderLayer.SOLID, true, true, 1L), "pre-growth draw");
            // Deny expansion without consuming ownership or corrupting the old page.
            blocker = budget.tryReserve(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU,
                limit - budget.snapshot().getGpuUsed());
            require(!uploadResult(arena, right, builder(.1F, .9F, 0, 0, 255, 3000), persistent),
                "expansion ignored total payload budget");
            require(arena.getOwnedMeshes() == 1 && arena.getAllocatedBufferBytes() == 256L * 1024L,
                "failed expansion changed old storage");
            require(arena.consumePublicationFailure() == null, "ordinary growth miss disabled terrain");
            require(pressureCallbacks[0] > 0, "growth pressure did not exercise reentrant collection");
            blocker.close(); blocker = null;
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, copyBinding);
            // No finish between the queued red draw and migration to the larger page.
            upload(arena, right, builder(.1F, .9F, 0, 0, 255, 3000), persistent);
            int beforeCopyReclaim = pressureCallbacks[0];
            require(ledger.reserveGpu(limit) == null && pressureCallbacks[0] > beforeCopyReclaim,
                "queued copy did not exercise collection before completion wait");
            require(arena.getOwnedMeshes() == 2, "copy-time collection discarded live mesh ownership");
            require(GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING) == copyBinding,
                "growth changed foreign copy-read binding");
            require(arena.getAllocatedBufferBytes() == 512L * 1024L, "page did not grow to actual extent");
            require(arena.render(new Container(right), BlockRenderLayer.SOLID, true, true, 2L), "post-growth draw");
            assertRgb(-.5F, 255, 0, 0); assertRgb(.5F, 0, 0, 255);
            setup();
            require(arena.render(new Container(left, right), BlockRenderLayer.SOLID, true, true, 3L), "copied old contents");
            assertRgb(-.5F, 255, 0, 0); assertRgb(.5F, 0, 0, 255);
            assertReadback(arena, left, "migration changed preserved mesh bytes");
            GL11.glFinish();
            upload(arena, extra, builder(-.9F, -.1F, 255, 0, 0, 1), persistent);
            require(arena.wasLastUploadPersistent() == persistent, "mapped uploads did not resume after copy");
            arena.reclaimUnusedPages(1L);
            blocker = budget.tryReserve(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU,
                limit - budget.snapshot().getGpuUsed());
            require(blocker != null && budget.snapshot().getGpuUsed() == limit, "test did not exhaust payload pool");
            long held = blocker.getBytes();
            arena.release(left.vbo); arena.release(right.vbo); arena.release(extra.vbo);
            arena.endFrame(true);
            require(arena.consumePublicationFailure() == null && arena.getStrandedRanges() == 0,
                "full budget turned retirement into permanent failure");
            GL11.glFinish(); arena.endFrame(true); GL11.glFinish();
            long reclaimed = arena.reclaimUnusedPages(1L);
            require(arena.getOwnedMeshes() == 0 && arena.getAllocatedBufferBytes() == 0
                && arena.allocatorStatus().getUsedBytes() == 0, "released terrain remained allocated");
            require(reclaimed >= 512L * 1024L, "completed empty page did not return payload budget");
            CacheBudget.Reservation peer = budget.tryReserve(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU, 512L * 1024L);
            require(peer != null, "peer cannot reuse released page space"); peer.close();
            require(budget.snapshot().getGpuUsed() >= held, "peer reservation lost");
            blocker.close(); blocker = null;
            TestChunk again = chunk();
            upload(arena, again, builder(-.9F, -.1F, 0, 255, 0, 1), persistent);
            setup(); require(arena.render(new Container(again), BlockRenderLayer.SOLID, true, true, 4L), "republish after pressure");
            assertRgb(-.5F, 0, 255, 0);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "elastic native GL error");
            System.out.println("elastic_page=true persistent=" + persistent
                + " initial_bytes=262144 grown_bytes=524288 queued_copy_pixels=exact"
                + " full_payload_budget_retirement=true peer_reuse=true republish=true"
                + " reentrant_growth_collection=true queued_copy_collection=true");
        } finally {
            if (blocker != null) blocker.close();
            arena.close(true); GL11.glFinish(); ledger.destroyAll(1L);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0); GL15.glDeleteBuffers(copyBinding);
            require(budget.snapshot().getGpuUsed() == 0L && budget.snapshot().getDirectUsed() == 0L,
                "final elastic budget did not return to zero");
        }
    }

    private static void capacityFitsThirteenFreeMiB(boolean persistent) throws Exception {
        long limit = 32L * MIB;
        CacheBudget budget = new CacheBudget(8L * MIB, 16L * MIB, limit);
        ResourceLedger ledger = ledger(RenderThreadGuard.captureCurrent(), budget);
        LwjglTerrainArena arena = new LwjglTerrainArena(RenderThreadGuard.captureCurrent(), ledger, budget,
            CapabilityReport.builder().pass(ModernCapability.MULTI_DRAW_INDIRECT)
                .pass(ModernCapability.PERSISTENT_MAPPING).build(), 32L * MIB, 1L);
        CacheBudget.Reservation blocker = null;
        try {
            // Reserve the command workspace before fixing the exact page-growth
            // headroom; the 32 MiB GPU limit remains unchanged.
            arena.configureNativeBatching(true, false);
            GpuArenaAllocator allocator = (GpuArenaAllocator) field(arena, "allocator");
            long extent = 8L * MIB - (8L * MIB) % 112L;
            ArenaRange filler = allocator.allocate(extent - 112L);
            TestChunk left = chunk(), right = chunk();
            upload(arena, left, builder(-.9F, -.1F, 255, 0, 0, 1), persistent);
            require(arena.getAllocatedBufferBytes() == 8L * MIB, "old page not 8MiB");
            setup(); arena.configureNativeBatching(true, false);
            require(arena.render(new Container(left), BlockRenderLayer.SOLID, true, true, 1L), "pre-fit draw");
            blocker = budget.tryReserve(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU,
                limit - budget.snapshot().getGpuUsed() - 13L * MIB);
            require(blocker != null && budget.availableBytes(
                dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU) == 13L * MIB, "13MiB test headroom");
            upload(arena, right, builder(.1F, .9F, 0, 0, 255, 10000), persistent);
            require(arena.getAllocatedBufferBytes() == 13L * MIB,
                "rounded 16MiB growth failed to use 13MiB free space");
            require(arena.getOwnedMeshes() == 2 && arena.consumePublicationFailure() == null,
                "fitted growth lost ownership or failed");
            require(arena.render(new Container(right), BlockRenderLayer.SOLID, true, true, 2L), "post-fit draw");
            assertRgb(-.5F, 255, 0, 0); assertRgb(.5F, 0, 0, 255);
            assertReadback(arena, left, "fitted page changed old bytes");
            assertReadback(arena, right, "fitted page changed new bytes");
            long physicalEnd = 13L * MIB - (13L * MIB) % 112L;
            ArenaRange filledPhysical = allocator.allocate(physicalEnd - extent - 10000L * 112L);
            GL11.glFinish(); arena.reclaimUnusedPages(1L); ledger.collect(1L, 128);
            require(budget.availableBytes(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU) == 8L * MIB,
                "old 8MiB page did not return its budget after copy");
            TestChunk anotherPage = chunk(), existingPage = chunk();
            upload(arena, anotherPage, builder(.1F, .9F, 0, 255, 0, 1), persistent);
            require(arena.getAllocatedBufferBytes() == 13L * MIB + 256L * 1024L,
                "full 13MiB physical page prevented a fresh small page allocation");
            require(allocator.snapshot().getCommittedBytes() == 2L * PAGE,
                "test did not commit the full logical arena");
            // The smaller logical tail in page0 must not outrank physical room
            // already present in page1, or every upload incurs another failure.
            long beforeExisting = arena.getAllocatedBufferBytes();
            long beforeRejected = budget.snapshot().getRejectedReservations();
            upload(arena, existingPage, builder(-.9F, -.1F, 255, 255, 0, 1), persistent);
            require(arena.getAllocatedBufferBytes() == beforeExisting,
                "retry did not use another committed page's existing physical hole");
            require(budget.snapshot().getRejectedReservations() == beforeRejected,
                "resident space was reached only after another rejected growth attempt");
            assertReadback(arena, left, "page retry changed old bytes");
            assertReadback(arena, anotherPage, "fresh-page retry wrote wrong offset");
            assertReadback(arena, existingPage, "committed-page retry wrote wrong offset");
            setup();
            require(arena.render(new Container(left, anotherPage), BlockRenderLayer.SOLID,
                true, true, 3L), "new page draw");
            assertRgb(-.5F, 255, 0, 0); assertRgb(.5F, 0, 255, 0);
            CacheBudget.Reservation full = budget.tryReserve(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU,
                budget.availableBytes(dev.rlcraft.ice.optimizer.memory.BudgetKind.GPU));
            try {
                TestChunk denied = chunk();
                long rejectedBeforeFull = budget.snapshot().getRejectedReservations();
                require(!uploadResult(arena, denied, builder(-.9F, -.1F, 0, 0, 0, 3000), persistent),
                    "no-fit allocation exceeded payload budget");
                require(budget.snapshot().getRejectedReservations() - rejectedBeforeFull <= 2L,
                    "full-budget retry repeated failing reservations for every page");
                require(arena.getOwnedMeshes() == 4 && arena.consumePublicationFailure() == null,
                    "exhausting all candidate pages destroyed live ownership");
            } finally { full.close(); }
            arena.release(left.vbo); arena.release(right.vbo);
            arena.release(anotherPage.vbo); arena.release(existingPage.vbo);
            allocator.free(filler); allocator.free(filledPhysical);
            arena.endFrame(true); GL11.glFinish(); arena.endFrame(true);
            GL11.glFinish(); arena.reclaimUnusedPages(1L);
            require(arena.getAllocatedBufferBytes() == 0L, "fitted page was not reclaimable");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "fitted capacity GL error");
            System.out.println("capacity_fit=true persistent=" + persistent
                + " old_mib=8 preferred_mib=16 available_mib=13 actual_mib=13"
                + " queued_pixels=exact payload_bytes=exact reclaimed=true"
                + " physical_full_next_page=true committed_full_other_page=true no_fit_fallback=true");
        } finally {
            if (blocker != null) blocker.close();
            arena.close(true); GL11.glFinish(); ledger.destroyAll(1L);
            require(budget.snapshot().getGpuUsed() == 0L
                && budget.snapshot().getDirectUsed() == 0L, "fitted capacity leaked budget");
        }
    }

    /** Readback belongs to this offline test, never to the production upload path. */
    private static void assertReadback(LwjglTerrainArena arena, TestChunk chunk, String detail)
            throws Exception {
        Object record = ((java.util.Map<?, ?>) field(arena, "meshes")).get(chunk.vbo);
        ArenaRange range = (ArenaRange) field(record, "range");
        long pageBytes = ((Long) field(arena, "pageBytes")).longValue();
        Object page = ((Object[]) field(arena, "bufferPages"))[(int) (range.getOffset() / pageBytes)];
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        ByteBuffer result = BufferUtils.createByteBuffer(chunk.expected.length);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ((Integer) field(page, "nativeId")).intValue());
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, range.getOffset() % pageBytes, result);
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        }
        byte[] actual = new byte[chunk.expected.length]; result.get(actual);
        require(Arrays.equals(chunk.expected, actual), detail);
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
    private static BufferBuilder builder(float x0, float x1, int r, int g, int b, int quads) {
        BufferBuilder builder = new BufferBuilder(quads * 112);
        builder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        for (int q = 0; q < quads; q++) for (int i = 0; i < 4; i++) {
            builder.pos(i == 1 || i == 2 ? x1 : x0, i >= 2 ? .8 : -.8, 0)
                .color(r, g, b, 255).tex(0, 0).lightmap(0, 0).endVertex();
        }
        builder.finishDrawing();
        return builder;
    }
    private static void setup() {
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
    private static TestChunk chunk() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        TestChunk chunk = (TestChunk) unsafe.allocateInstance(TestChunk.class);
        chunk.vbo = (TestBuffer) unsafe.allocateInstance(TestBuffer.class);
        chunk.compiled = new CompiledChunk();
        return chunk;
    }
    private static void require(boolean valid, String detail) {
        if (!valid) throw new AssertionError(detail);
    }
    private static boolean crossPage() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return caps.GL_NV_bindless_multi_draw_indirect && caps.GL_NV_vertex_buffer_unified_memory
            && caps.GL_NV_shader_buffer_load;
    }
    private static final class TestChunk extends RenderChunk {
        private TestBuffer vbo;
        private CompiledChunk compiled;
        private byte[] expected;
        private TestChunk() { super(null, null, 0); }
        @Override public BlockPos getPosition() { return BlockPos.ORIGIN; }
        @Override public VertexBuffer getVertexBufferByLayer(int layer) { return vbo; }
        @Override public CompiledChunk getCompiledChunk() { return compiled; }
    }
    private static final class TestBuffer extends VertexBuffer implements ChunkVertexBufferAccessor {
        private TestBuffer() { super(DefaultVertexFormats.BLOCK); }
        public int ice$glBufferId() { return 0; }
        public int ice$vertexCount() { return 0; }
        public int ice$vertexStrideBytes() { return 28; }
        public int ice$capacityBytes() { return 0; }
        public void ice$setCapacityBytes(int bytes) { }
        public void ice$setVertexCount(int vertices) { }
    }
    private static final class Container implements TerrainRenderListAccessor {
        private final List<RenderChunk> chunks = new ArrayList<RenderChunk>();
        private Container(RenderChunk... chunks) { this.chunks.addAll(Arrays.asList(chunks)); }
        public List<RenderChunk> ice$renderChunks() { return chunks; }
        public boolean ice$initialized() { return true; }
        public double ice$viewEntityX() { return 0; }
        public double ice$viewEntityY() { return 0; }
        public double ice$viewEntityZ() { return 0; }
    }
}
