package dev.rlcraft.ice.optimizer.render.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class ModelMeshStorageTest {
    @Test public void admissionProgressCollectsReusableRangesWithoutReattemptingUploads() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        fixture.allocate();
        long before = fixture.storage.admissionProgress();
        assertNull(fixture.storage.allocate(96, fixture.stamp));
        assertTrue(fixture.storage.lastAllocationBlockedByCapacity());
        assertFalse(fixture.storage.lastAllocationChangedBinding());
        assertEquals(before, fixture.storage.admissionProgress());
        fixture.storage.retire(first);
        assertEquals(before, fixture.storage.admissionProgress());
        fixture.driver.fences.get(0).signaled = true;
        assertEquals(before + 1, fixture.storage.admissionProgress());
        assertEquals(1, fixture.driver.creates);
        assertNotNull(fixture.storage.allocate(96, fixture.stamp));
        assertEquals(1, fixture.driver.creates);
    }

    @Test public void vertexArrayBelongsToSharedPageAndRetiresWithLastPart() {
        Fixture fixture = new Fixture(192L
            + ResourceLedger.nativeObjectCharge(RenderResourceKind.VERTEX_ARRAY));
        ModelMeshStorage.Allocation first = fixture.allocate();
        ModelMeshStorage.Allocation second = fixture.allocate();
        dev.rlcraft.ice.optimizer.render.resource.RenderHandle vao =
            fixture.ledger.registerReservedObject(RenderResourceKind.VERTEX_ARRAY, 77,
                1, 1, fixture.ledger.reserveNativeObject(RenderResourceKind.VERTEX_ARRAY));
        assertNotNull(vao);
        first.vertexArray(vao);
        assertSame(vao, second.vertexArray());
        fixture.storage.retire(first);
        assertTrue(fixture.ledger.isLive(vao));
        fixture.storage.retire(second);
        assertFalse(fixture.ledger.isLive(vao));
        assertEquals(2, fixture.ledger.snapshot().getRetired());
        assertEquals(0, fixture.storage.pageCount());
        assertNull(first.vertexArray());
    }

    @Test public void partsShareOnePageWithIndependentVertexOffsetsAndIdentities() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        ModelMeshStorage.Allocation second = fixture.allocate();
        assertSame(first.handle(), second.handle());
        assertEquals(0, first.firstVertex);
        assertEquals(4, second.firstVertex);
        assertTrue(first.id != second.id);
        assertEquals(1, fixture.driver.creates);
        assertEquals(192L, fixture.budget.snapshot().getGpuUsed());
        fixture.storage.upload(first, ByteBuffer.wrap(new byte[96]));
        ByteBuffer secondBytes = ByteBuffer.allocate(96);
        secondBytes.put(0, (byte) 7);
        fixture.storage.upload(second, secondBytes);
        byte[] page = fixture.driver.buffers.get(first.handle().getNativeId());
        assertEquals(0, page[0]);
        assertEquals(7, page[96]);
    }

    @Test public void retiredRangeCannotBeOverwrittenBeforeItsFenceSignals() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        ModelMeshStorage.Allocation second = fixture.allocate();
        fixture.storage.retire(first);
        assertFalse(first.isLive());
        assertNull(fixture.storage.allocate(96, fixture.stamp));
        assertTrue(second.isLive());
        fixture.driver.fences.get(0).signaled = true;
        ModelMeshStorage.Allocation replacement = fixture.allocate();
        assertEquals(first.firstVertex, replacement.firstVertex);
        assertTrue(first.id != replacement.id);
        assertEquals(1, fixture.driver.creates);
        assertEquals(1, fixture.driver.fences.get(0).destroys);
    }

    @Test public void cancelledPublicationRangeCanBeReusedWithoutAReadFence() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        fixture.allocate();
        fixture.storage.cancel(first);
        ModelMeshStorage.Allocation replacement = fixture.allocate();
        assertEquals(first.firstVertex, replacement.firstVertex);
        assertEquals(0, fixture.driver.fences.size());
        assertFalse(first.isLive());
        assertEquals(1024L, fixture.budget.snapshot().getHeapUsed());
    }

    @Test public void lastLiveRangeRetiresWholePageThroughLedgerOnly() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        ModelMeshStorage.Allocation second = fixture.allocate();
        fixture.storage.retire(first);
        fixture.storage.retire(second);
        assertEquals(0, fixture.storage.pageCount());
        assertEquals(1, fixture.ledger.snapshot().getRetired());
        assertEquals(0L, fixture.budget.snapshot().getHeapUsed());
        assertEquals(192L, fixture.budget.snapshot().getGpuUsed());
        fixture.ledger.collect(1, 8);
        assertEquals(0, fixture.driver.deletes);
        fixture.driver.fences.get(1).signaled = true;
        fixture.ledger.collect(1, 8);
        assertEquals(1, fixture.driver.deletes);
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
        assertFalse(second.isLive());
    }

    @Test public void contextLossDropsMetadataWithoutCallingNativeFenceDelete() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        fixture.allocate();
        fixture.storage.retire(first);
        fixture.storage.reset(false);
        assertEquals(0, fixture.driver.fences.get(0).destroys);
        assertEquals(0, fixture.driver.deletes);
        assertEquals(0L, fixture.budget.snapshot().getHeapUsed());
        fixture.ledger.abandonContext(1);
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
    }

    @Test public void zeroGpuBudgetDeclinesBeforeNativeAllocation() {
        Fixture fixture = new Fixture(0);
        assertNull(fixture.storage.allocate(96, fixture.stamp));
        assertEquals(0, fixture.driver.creates);
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
        assertEquals(0L, fixture.budget.snapshot().getHeapUsed());
    }

    @Test public void knownNativeAllocationFailureDeletesNameAndRollsBackBudget() {
        Fixture fixture = new Fixture(192);
        fixture.driver.failAllocate = true;
        try {
            fixture.storage.allocate(96, fixture.stamp);
            fail("allocation failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected buffer allocation failure", expected.getMessage());
        }
        assertEquals(1, fixture.driver.deletes);
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
        assertEquals(0, fixture.storage.pageCount());
    }

    @Test public void failedRangeFenceRetainsRangeInsteadOfEnablingReuse() {
        Fixture fixture = new Fixture(192);
        ModelMeshStorage.Allocation first = fixture.allocate();
        fixture.allocate();
        fixture.storage.retire(first);
        fixture.driver.fences.get(0).fail = true;
        try {
            fixture.storage.collect(8);
            fail("fence failure must escape");
        } catch (IllegalStateException expected) {
            assertEquals("injected fence failure", expected.getMessage());
        }
        assertNull(fixture.storage.allocate(96, fixture.stamp));
        assertEquals(1024L, fixture.budget.snapshot().getHeapUsed());
    }

    @Test public void resetRetiresEachPageOnceAndClearsAllRanges() {
        Fixture fixture = new Fixture(384);
        fixture.allocate();
        fixture.allocate();
        fixture.allocate();
        assertEquals(2, fixture.storage.pageCount());
        fixture.storage.reset(true);
        fixture.storage.reset(true);
        assertEquals(2, fixture.ledger.snapshot().getRetired());
        assertEquals(0L, fixture.budget.snapshot().getHeapUsed());
        for (Fence fence : fixture.driver.fences) fence.signaled = true;
        fixture.ledger.collect(1, 8);
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
    }

    private static final class Fixture {
        private final CacheBudget budget;
        private final Driver driver = new Driver();
        private final ResourceLedger ledger;
        private final ModelMeshStorage storage;
        private final FrameStamp stamp;

        private Fixture(long gpuBytes) {
            budget = new CacheBudget(1 << 20, 1 << 20, gpuBytes);
            ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(),
                budget, new ResourceLedger.Destroyer() {
                    @Override public void destroy(RenderResourceKind kind, int id) {
                        driver.delete(id);
                    }
                }, 64);
            storage = new ModelMeshStorage(ledger, budget, 24, 192, driver);
            ClientEpochs epochs = new ClientEpochs();
            epochs.nextFrame();
            stamp = new FrameStamp(1, 1, epochs.snapshot());
        }

        private ModelMeshStorage.Allocation allocate() {
            ModelMeshStorage.Allocation allocation = storage.allocate(96, stamp);
            assertNotNull(allocation);
            return allocation;
        }
    }

    private static final class Driver implements ModelMeshStorage.Driver {
        private final Map<Integer, byte[]> buffers = new HashMap<Integer, byte[]>();
        private final ArrayList<Fence> fences = new ArrayList<Fence>();
        private int creates;
        private int deletes;
        private boolean failAllocate;
        @Override public int create() { return ++creates; }
        @Override public void allocate(int buffer, int bytes) {
            if (failAllocate) throw new IllegalStateException("injected buffer allocation failure");
            buffers.put(buffer, new byte[bytes]);
        }
        @Override public void upload(int buffer, long offset, ByteBuffer vertices) {
            ByteBuffer copy = vertices.duplicate();
            copy.get(buffers.get(buffer), (int) offset, copy.remaining());
        }
        @Override public void delete(int buffer) { deletes++; buffers.remove(buffer); }
        @Override public ResourceLedger.RetirementFence fence(ResourceLedger ledger) {
            Fence fence = new Fence();
            fences.add(fence);
            return fence;
        }
    }

    private static final class Fence implements ResourceLedger.RetirementFence {
        private boolean signaled;
        private boolean fail;
        private int destroys;
        @Override public boolean isSignaled() {
            if (fail) throw new IllegalStateException("injected fence failure");
            return signaled;
        }
        @Override public void destroy() { destroys++; }
    }
}
