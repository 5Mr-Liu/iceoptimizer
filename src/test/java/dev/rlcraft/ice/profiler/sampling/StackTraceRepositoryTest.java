package dev.rlcraft.ice.profiler.sampling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        assertTrue(repository.size() <= 3); // overflow sentinel + two real traces
    }

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, owner + ".java", 1);
    }
}
