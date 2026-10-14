package dev.rlcraft.ice.optimizer.render.entity;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.*;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.Test;

public final class ModelMeshAdmissionTest {
    @Test public void gpuBudgetMissDoesNotCopyVerticesOrTouchNativeBindings() throws Exception {
        Fixture fixture = new Fixture(4096, 1);
        assertTrue(fixture.cache.ensureStaging(96));
        Field field = LwjglModelMeshCache.class.getDeclaredField("staging"); field.setAccessible(true);
        ByteBuffer staging = (ByteBuffer) field.get(fixture.cache);
        for (int i = 0; i < 96; i++) staging.put(i, (byte) 0x35);
        staging.position(11); staging.limit(111);
        EarlyGlStateTracker.bindBuffer(34962, 0);
        // There is deliberately no native GL context. The old unconditional
        // glBindBuffer cleanup fails here even though nothing was allocated.
        assertFalse(fixture.cache.accept(payload(), fixture.stamp));
        assertTrue(fixture.cache.lastAdmissionBlockedByCapacity());
        assertEquals(11, staging.position()); assertEquals(111, staging.limit());
        for (int i = 0; i < 96; i++) assertEquals((byte) 0x35, staging.get(i));
        assertEquals(0, fixture.ledger.snapshot().getCreated());
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
        fixture.cache.close(false);
    }

    @Test public void directBudgetWakeupRequiresEnoughBytesRatherThanAnyPassingFrame() {
        Fixture fixture = new Fixture(4096, 98304);
        CacheBudget.Reservation occupied = fixture.budget.tryReserve(BudgetKind.DIRECT, 4096);
        EarlyGlStateTracker.bindBuffer(34962, 0);
        assertFalse(fixture.cache.accept(payload(), fixture.stamp));
        assertTrue(fixture.cache.lastAdmissionBlockedByCapacity());
        long before = fixture.cache.admissionProgress();
        for (int i = 0; i < 1000; i++) assertEquals(before, fixture.cache.admissionProgress());
        occupied.close();
        assertEquals(before + 1, fixture.cache.admissionProgress());
        assertEquals(before + 1, fixture.cache.admissionProgress());
        fixture.cache.close(false);
    }

    @Test public void retainedGeometryBudgetWaitsForRealHeapSpaceWithoutTouchingNativeState() {
        Fixture fixture = new Fixture(4096, 98304);
        CacheBudget.Reservation occupied = fixture.budget.tryReserve(BudgetKind.HEAP, 1 << 20);
        EarlyGlStateTracker.bindBuffer(34962, 0);
        // No GL context: reserving geometry must precede native storage creation.
        assertFalse(fixture.cache.accept(payload(), fixture.stamp));
        assertTrue(fixture.cache.lastAdmissionBlockedByCapacity());
        long before = fixture.cache.admissionProgress();
        for (int i = 0; i < 1000; i++) assertEquals(before, fixture.cache.admissionProgress());
        occupied.close();
        assertEquals(before + 1, fixture.cache.admissionProgress());
        assertEquals(before + 1, fixture.cache.admissionProgress());
        assertEquals(0, fixture.ledger.snapshot().getCreated());
        fixture.cache.close(false);
        assertEquals(0L, fixture.budget.snapshot().getHeapUsed());
    }

    @Test public void sourceAloneFittingDoesNotCreateAndRetireAnUnusablePageEveryFrame() {
        Fixture fixture = new Fixture(4096, 98304);
        long sourceBytes = payload().getByteLength();
        CacheBudget.Reservation occupied = fixture.budget.tryReserve(BudgetKind.HEAP, (1 << 20) - sourceBytes);
        EarlyGlStateTracker.bindBuffer(34962, 0);
        assertFalse(fixture.cache.accept(payload(), fixture.stamp));
        assertTrue(fixture.cache.lastAdmissionBlockedByCapacity());
        long progress = fixture.cache.admissionProgress();
        for (int i = 0; i < 1000; i++) assertEquals(progress, fixture.cache.admissionProgress());
        assertEquals(0, fixture.ledger.snapshot().getCreated());
        occupied.close();
        CacheBudget.Reservation stillOccupied = fixture.budget.tryReserve(BudgetKind.HEAP,
            (1 << 20) - sourceBytes - GpuArenaAllocator.rangeHeapBytes() + 1);
        assertEquals(progress, fixture.cache.admissionProgress());
        stillOccupied.close();
        assertEquals(progress + 1, fixture.cache.admissionProgress());
        fixture.cache.close(false);
    }

    private static ModelMeshPayload payload() {
        byte[] vertices = new byte[96]; Arrays.fill(vertices, (byte) 0x7f);
        return new ModelMeshPayload(1, vertices, DefaultVertexFormats.POSITION_TEX_NORMAL, 7, 4, 1, 1);
    }

    private static final class Fixture {
        final CacheBudget budget;
        final ResourceLedger ledger;
        final LwjglModelMeshCache cache;
        final FrameStamp stamp;
        Fixture(long direct, long gpu) {
            budget = new CacheBudget(1 << 20, direct, gpu);
            RenderThreadGuard guard = RenderThreadGuard.captureCurrent();
            ledger = new ResourceLedger(guard, budget, new ResourceLedger.Destroyer() {
                public void destroy(RenderResourceKind kind, int id) { }
            }, 128);
            cache = new LwjglModelMeshCache(guard, ledger, budget);
            ClientEpochs epochs = new ClientEpochs(); epochs.nextFrame();
            stamp = new FrameStamp(1, 1, epochs.snapshot());
        }
    }
}
