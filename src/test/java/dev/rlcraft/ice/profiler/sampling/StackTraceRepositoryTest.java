package dev.rlcraft.ice.profiler.sampling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

public class StackTraceRepositoryTest {
    @Test
    public void deduplicatesAndBoundsDictionary() {
        StackTraceRepository repository = new StackTraceRepository(2, 2);
        StackTraceElement[] one = { frame("a.A", "one"), frame("a.B", "two"), frame("a.C", "three") };
        int first = repository.intern(one);
        assertEquals(first, repository.intern(one));
        assertEquals(2, repository.get(first).length);
        repository.intern(new StackTraceElement[] { frame("b.B", "two") });
        assertEquals(StackTraceRepository.OVERFLOW_ID, repository.intern(new StackTraceElement[] { frame("c.C", "three") }));
        assertEquals(1L, repository.getOverflowCount());
        assertEquals(0L, repository.getPrefixMergedCount());
        assertEquals(1L, repository.getDroppedCount());
        assertTrue(repository.size() <= 3); // overflow sentinel + two real traces
    }

    @Test
    public void fullDictionaryMergesLineVariantsIntoStablePrefixRepresentative() {
        StackTraceRepository repository = new StackTraceRepository(1, 8);
        int representative = repository.intern(new StackTraceElement[] {
            frame("a.A", "leaf", 1), frame("b.B", "middle", 2),
            frame("c.C", "root", 3), frame("d.D", "first", 4)
        });

        int merged = repository.intern(new StackTraceElement[] {
            frame("a.A", "leaf", 101), frame("b.B", "middle", 102),
            frame("c.C", "root", 103), frame("z.Z", "different", 104)
        });

        assertEquals(representative, merged);
        assertEquals(1L, repository.getOverflowCount());
        assertEquals(1L, repository.getPrefixMergedCount());
        assertEquals(0L, repository.getDroppedCount());
    }

    @Test
    public void dictionaryNeverRetainsTheCallersMutableArray() {
        StackTraceRepository repository = new StackTraceRepository(4, 4);
        StackTraceElement original = frame("safe.A", "run", 7);
        StackTraceElement[] callerOwned = new StackTraceElement[] { original };
        int id = repository.intern(callerOwned);
        callerOwned[0] = frame("mutated.B", "other", 99);

        assertEquals(original, repository.get(id)[0]);
        assertEquals(id, repository.intern(new StackTraceElement[] { original }));
    }

    @Test
    public void droppedHotspotSketchAndStatisticsWindowRemainFixedSize() {
        StackTraceRepository repository = new StackTraceRepository(1, 4);
        repository.intern(new StackTraceElement[] { frame("kept.A", "run") });
        StackTraceRepository.StatisticsWindow window =
            repository.beginStatisticsWindow();
        for (int i = 0; i < 100; i++) {
            repository.intern(new StackTraceElement[] {
                frame("drop.C" + i, "m" + i)
            });
        }

        StackTraceRepository.Statistics statistics =
            repository.statisticsSince(window);
        assertEquals(100L, statistics.getOverflow());
        assertEquals(0L, statistics.getPrefixMerged());
        assertEquals(100L, statistics.getDropped());
        assertEquals(16, statistics.getHotspots().size());
        assertTrue(statistics.getHotspots().get(0).getEstimatedCount() >= 1L);
        assertTrue(repository.size() <= 2);
    }

    @Test
    public void concurrentOverflowAccountingIsExactAndBounded() throws Exception {
        final StackTraceRepository repository = new StackTraceRepository(1, 4);
        repository.intern(new StackTraceElement[] {
            frame("same.A", "leaf", 1), frame("same.B", "middle", 1),
            frame("same.C", "root", 1)
        });
        final int threads = 8;
        final int samples = 200;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Throwable> failures = new ArrayList<Throwable>();
        List<Thread> workers = new ArrayList<Thread>();
        for (int thread = 0; thread < threads; thread++) {
            final int offset = thread;
            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < samples; i++) {
                            repository.intern(new StackTraceElement[] {
                                frame("same.A", "leaf", 10 + offset + i),
                                frame("same.B", "middle", 20 + i),
                                frame("same.C", "root", 30 + i),
                                frame("tail.T" + offset, "run", i)
                            });
                        }
                    } catch (Throwable error) {
                        synchronized (failures) { failures.add(error); }
                    }
                }
            }, "stack-repository-test-" + thread);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();

        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals((long) threads * samples, repository.getOverflowCount());
        assertEquals((long) threads * samples,
            repository.getPrefixMergedCount());
        assertEquals(0L, repository.getDroppedCount());
        assertEquals(2, repository.size());
    }

    @Test
    public void overlappingStatisticsWindowsKeepIndependentHotspotEvidence() {
        StackTraceRepository repository = new StackTraceRepository(1, 4);
        repository.intern(new StackTraceElement[] { frame("kept.A", "run") });
        StackTraceRepository.StatisticsWindow first =
            repository.beginStatisticsWindow();
        repository.intern(new StackTraceElement[] { frame("drop.First", "run") });
        StackTraceRepository.StatisticsWindow second =
            repository.beginStatisticsWindow();
        repository.intern(new StackTraceElement[] { frame("drop.Second", "run") });

        StackTraceRepository.Statistics firstResult =
            repository.finishStatisticsWindow(first);
        StackTraceRepository.Statistics secondResult =
            repository.finishStatisticsWindow(second);
        assertEquals(2L, firstResult.getDropped());
        assertEquals(2, firstResult.getHotspots().size());
        assertEquals(1L, secondResult.getDropped());
        assertEquals(1, secondResult.getHotspots().size());
        assertTrue(secondResult.getHotspots().get(0).getPrefix()
            .contains("drop.Second.run"));
    }

    @Test
    public void finishedStatisticsWindowRemainsFrozenAfterLaterSamples() {
        StackTraceRepository repository = new StackTraceRepository(1, 4);
        repository.intern(new StackTraceElement[] { frame("kept.A", "run") });
        StackTraceRepository.StatisticsWindow window =
            repository.beginStatisticsWindow();
        repository.intern(new StackTraceElement[] { frame("drop.First", "run") });

        StackTraceRepository.Statistics finished =
            repository.finishStatisticsWindow(window);
        repository.intern(new StackTraceElement[] { frame("drop.Later", "run") });
        StackTraceRepository.Statistics observed =
            repository.statisticsSince(window);

        assertEquals(1L, finished.getOverflow());
        assertEquals(1L, finished.getDropped());
        assertEquals(finished.getOverflow(), observed.getOverflow());
        assertEquals(finished.getPrefixMerged(), observed.getPrefixMerged());
        assertEquals(finished.getDropped(), observed.getDropped());
        assertEquals(finished.getHotspots(), observed.getHotspots());
    }

    private static StackTraceElement frame(String owner, String method) {
        return frame(owner, method, 1);
    }

    private static StackTraceElement frame(String owner, String method,
                                           int line) {
        return new StackTraceElement(owner, method, owner + ".java", line);
    }
}
