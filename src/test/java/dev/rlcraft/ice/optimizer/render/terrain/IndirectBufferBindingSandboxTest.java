package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class IndirectBufferBindingSandboxTest {
    @Test
    public void invalidationReauthenticatesThenRestoresAfterSubmission() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.seedDrawIndirectBuffer(3);
        EarlyGlStateTracker.invalidate();
        FakeAccess access = new FakeAccess(17);

        IndirectBufferBindingSandbox.Lease lease =
            IndirectBufferBindingSandbox.acquire(access);
        assertTrue(lease.queried());
        assertEquals(17, lease.previous());
        lease.bind(29);
        access.submissions++;
        lease.restore();

        assertEquals(1, access.queries);
        assertEquals(1, access.submissions);
        assertEquals(17, EarlyGlStateTracker.drawIndirectBufferBinding());
        assertEquals("query", access.events.get(0));
        assertEquals("publish:17", access.events.get(1));
        assertEquals("bind:29", access.events.get(2));
        assertEquals("bind:17", access.events.get(3));
    }

    @Test
    public void restoreFailureInvalidatesTheCertifiedBinding() {
        EarlyGlStateTracker.beginProbe();
        EarlyGlStateTracker.seedDrawIndirectBuffer(7);
        FakeAccess access = new FakeAccess(7);
        access.failBinding = 7;
        IndirectBufferBindingSandbox.Lease lease =
            IndirectBufferBindingSandbox.acquire(access);
        lease.bind(31);

        try {
            lease.restore();
            fail("restore failure must escape");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        assertEquals(Integer.MIN_VALUE,
            EarlyGlStateTracker.drawIndirectBufferBinding());
        assertEquals(1, access.invalidations);
    }

    private static final class FakeAccess
        implements IndirectBufferBindingSandbox.Access {
        private final int queriedBinding;
        private final List<String> events = new ArrayList<String>();
        private int failBinding = Integer.MIN_VALUE;
        private int queries;
        private int submissions;
        private int invalidations;

        private FakeAccess(int queriedBinding) {
            this.queriedBinding = queriedBinding;
        }

        @Override public int trackedBinding() {
            return EarlyGlStateTracker.drawIndirectBufferBinding();
        }

        @Override public int queryBinding() {
            queries++;
            events.add("query");
            return queriedBinding;
        }

        @Override public void publishBinding(int nativeId) {
            events.add("publish:" + nativeId);
            EarlyGlStateTracker.seedDrawIndirectBuffer(nativeId);
        }

        @Override public void bind(int nativeId) {
            events.add("bind:" + nativeId);
            if (nativeId == failBinding) {
                throw new IllegalStateException("injected restore failure");
            }
            EarlyGlStateTracker.seedDrawIndirectBuffer(nativeId);
        }

        @Override public void invalidate() {
            invalidations++;
            EarlyGlStateTracker.invalidate();
        }
    }
}
