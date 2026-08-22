package dev.rlcraft.ice.optimizer.render.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class TemporaryGpuResourceScopeTest {
    @Test
    public void reservesBeforeAllocationAndReleasesAfterConfirmedDelete() {
        CacheBudget budget = budget(16384L);
        final AtomicInteger deletes = new AtomicInteger();
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot slot = scope.reserve(
            RenderResourceKind.BUFFER, 4096L,
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    assertEquals(19, nativeId);
                    deletes.incrementAndGet();
                }
            });
        assertEquals(4096L, budget.snapshot().getGpuUsed());
        assertEquals(19, slot.allocate(
            new TemporaryGpuResourceScope.IntAllocator() {
                @Override public int allocate() { return 19; }
            }));

        scope.close();

        assertEquals(1, deletes.get());
        assertTrue(slot.wasAllocationAttempted());
        assertTrue(slot.didAllocationReturn());
        assertTrue(slot.wasDeletionCompleted());
        assertFalse(slot.isReservationPoisoned());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void allocatorWithUnknownOutcomePoisonsReservation() {
        CacheBudget budget = budget(16384L);
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 1);
        TemporaryGpuResourceScope.Slot slot = scope.reserve(
            RenderResourceKind.TEXTURE, 2048L, noDelete());
        try {
            slot.allocate(new TemporaryGpuResourceScope.IntAllocator() {
                @Override public int allocate() {
                    throw new IllegalStateException("injected allocation failure");
                }
            });
            throw new AssertionError("expected allocator failure");
        } catch (IllegalStateException expected) {
            assertEquals("injected allocation failure", expected.getMessage());
        }

        scope.close();

        assertTrue(slot.wasAllocationAttempted());
        assertFalse(slot.didAllocationReturn());
        assertTrue(slot.isReservationPoisoned());
        assertEquals(2048L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void deletionWithUnknownOutcomePoisonsReservationAndReportsFailure() {
        CacheBudget budget = budget(16384L);
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 1);
        TemporaryGpuResourceScope.Slot slot = scope.reserve(
            RenderResourceKind.FRAMEBUFFER, 4096L,
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    throw new IllegalStateException("injected delete failure");
                }
            });
        slot.allocate(new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return 7; }
        });
        Throwable operation = new IllegalArgumentException("operation");

        Throwable combined = scope.closeAndAppend(operation);

        assertTrue(combined == operation);
        assertEquals(1, combined.getSuppressed().length);
        assertEquals("injected delete failure",
            combined.getSuppressed()[0].getMessage());
        assertFalse(slot.wasDeletionCompleted());
        assertTrue(slot.isReservationPoisoned());
        assertEquals(4096L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void returnedZeroAndUnattemptedSlotsReleaseWithoutDelete() {
        CacheBudget budget = budget(16384L);
        final AtomicInteger deletes = new AtomicInteger();
        TemporaryGpuResourceScope.IntDestroyer destroyer =
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    deletes.incrementAndGet();
                }
            };
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot returnedZero = scope.reserve(
            RenderResourceKind.BUFFER, 1024L, destroyer);
        scope.reserve(RenderResourceKind.TEXTURE, 2048L, destroyer);
        returnedZero.allocate(new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return 0; }
        });

        scope.close();

        assertEquals(0, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void duplicateNameInOneNamespaceIsDeletedOnce() {
        CacheBudget budget = budget(16384L);
        final AtomicInteger deletes = new AtomicInteger();
        TemporaryGpuResourceScope.IntDestroyer destroyer =
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    deletes.incrementAndGet();
                }
            };
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot first = scope.reserve(
            RenderResourceKind.QUERY, 4096L, destroyer);
        TemporaryGpuResourceScope.Slot duplicate = scope.reserve(
            RenderResourceKind.QUERY, 4096L, destroyer);
        TemporaryGpuResourceScope.IntAllocator allocator =
            new TemporaryGpuResourceScope.IntAllocator() {
                @Override public int allocate() { return 31; }
            };
        first.allocate(allocator);
        duplicate.allocate(allocator);
        assertEquals(4096L, budget.snapshot().getGpuUsed());

        scope.close();

        assertEquals(1, deletes.get());
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void cleanupUsesReverseReservationOrder() {
        CacheBudget budget = budget(16384L);
        final List<Integer> order = new ArrayList<Integer>();
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 3);
        for (int id = 1; id <= 3; id++) {
            final int value = id;
            TemporaryGpuResourceScope.Slot slot = scope.reserve(
                RenderResourceKind.BUFFER, 1024L,
                new TemporaryGpuResourceScope.IntDestroyer() {
                    @Override public void destroy(int nativeId) {
                        order.add(Integer.valueOf(nativeId));
                    }
                });
            slot.allocate(new TemporaryGpuResourceScope.IntAllocator() {
                @Override public int allocate() { return value; }
            });
        }

        scope.close();

        assertEquals(Arrays.asList(Integer.valueOf(3), Integer.valueOf(2),
            Integer.valueOf(1)), order);
    }

    @Test
    public void failedFramebufferDeletePoisonsAttachedTextureAccounting() {
        CacheBudget budget = budget(16384L);
        final List<Integer> order = new ArrayList<Integer>();
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot framebuffer = scope.reserve(
            RenderResourceKind.FRAMEBUFFER, 4096L,
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    order.add(Integer.valueOf(nativeId));
                    throw new IllegalStateException("framebuffer delete");
                }
            });
        TemporaryGpuResourceScope.Slot texture = scope.reserve(
            RenderResourceKind.TEXTURE, 4096L,
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    order.add(Integer.valueOf(nativeId));
                }
            });
        framebuffer.allocate(new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return 11; }
        });
        texture.allocate(new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return 22; }
        });

        Throwable failure = scope.closeAndAppend(null);

        assertEquals("container must be deleted before its attachment",
            Arrays.asList(Integer.valueOf(11), Integer.valueOf(22)), order);
        assertEquals("framebuffer delete", failure.getMessage());
        assertTrue(framebuffer.isReservationPoisoned());
        assertTrue(texture.isReservationPoisoned());
        assertEquals(8192L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void failedVertexArrayDeletePoisonsReferencedBufferAccounting() {
        CacheBudget budget = budget(16384L);
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 2);
        TemporaryGpuResourceScope.Slot buffer = scope.reserve(
            RenderResourceKind.BUFFER, 4096L, noDelete());
        TemporaryGpuResourceScope.Slot vertexArray = scope.reserve(
            RenderResourceKind.VERTEX_ARRAY, 4096L,
            new TemporaryGpuResourceScope.IntDestroyer() {
                @Override public void destroy(int nativeId) {
                    throw new IllegalStateException("VAO delete");
                }
            });
        buffer.allocate(new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return 31; }
        });
        vertexArray.allocate(new TemporaryGpuResourceScope.IntAllocator() {
            @Override public int allocate() { return 32; }
        });

        scope.closeAndAppend(null);

        assertTrue(buffer.isReservationPoisoned());
        assertTrue(vertexArray.isReservationPoisoned());
        assertEquals(8192L, budget.snapshot().getGpuUsed());
    }

    @Test
    public void hardBudgetRejectionOccursBeforeAllocatorCanRun() {
        CacheBudget budget = budget(4096L);
        TemporaryGpuResourceScope scope =
            new TemporaryGpuResourceScope(budget, 2);
        assertTrue(scope.reserve(RenderResourceKind.BUFFER, 4096L,
            noDelete()) != null);
        assertNull(scope.reserve(RenderResourceKind.TEXTURE, 1L, noDelete()));
        scope.close();
        assertEquals(0L, budget.snapshot().getGpuUsed());
    }

    private static CacheBudget budget(long gpuBytes) {
        return new CacheBudget(1L, 1L, gpuBytes);
    }

    private static TemporaryGpuResourceScope.IntDestroyer noDelete() {
        return new TemporaryGpuResourceScope.IntDestroyer() {
            @Override public void destroy(int nativeId) { }
        };
    }
}
