package dev.rlcraft.ice.optimizer.compat.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.lang.reflect.Field;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelQuadruped;
import static org.junit.Assert.assertSame;
import org.junit.Test;

public final class ModelStateBatchBridgeTest {
    @Test
    public void optifineSpriteListAdmissionDoesNotInvokeUntrustedListCallbacks() {
        assertTrue(ModelStateBatchBridge.isEmptyCanonicalList(new ArrayList<Object>()));
        ArrayList<Object> nonempty = new ArrayList<Object>();
        nonempty.add(new Object());
        assertFalse(ModelStateBatchBridge.isEmptyCanonicalList(nonempty));
        assertFalse(ModelStateBatchBridge.isEmptyCanonicalList(null));
        assertFalse(ModelStateBatchBridge.isEmptyCanonicalList(new ArrayList<Object>() {
            @Override public boolean isEmpty() { throw new AssertionError("untrusted callback"); }
        }));
    }

    @Test
    public void inactiveDrawAndScopeCloseNeedNoOpenGlContext() {
        assertFalse(ModelStateBatchBridge.isActive());
        assertFalse(ModelStateBatchBridge.draw(9));
        assertFalse(ModelStateBatchBridge.begin(null));
        ModelStateBatchBridge.end();
        ModelStateBatchBridge.endAfterFailure(new IllegalStateException("original"));
    }

    @Test
    public void activeRenderScopeDoesNotAuthorizeAnotherThreadsTransformChain() throws Exception {
        Field states = ModelStateBatchBridge.class.getDeclaredField("STATES");
        states.setAccessible(true);
        Object state = ((ThreadLocal<?>) states.get(null)).get();
        Field active = state.getClass().getDeclaredField("active");
        active.setAccessible(true);
        Field globalActive = ModelStateBatchBridge.class.getDeclaredField("hasActiveBatch");
        globalActive.setAccessible(true);
        final boolean[] workerActive = {true};
        try {
            active.setBoolean(state, true);
            globalActive.setBoolean(null, true);
            assertTrue(ModelStateBatchBridge.isActive());
            Thread worker = new Thread(new Runnable() {
                @Override public void run() { workerActive[0] = ModelStateBatchBridge.isActive(); }
            });
            worker.start();
            worker.join();
            assertFalse(workerActive[0]);
        } finally {
            active.setBoolean(state, false);
            globalActive.setBoolean(null, false);
        }
    }

    @Test
    public void declinedRootScopeClosesWithoutAttemptingClientStatePop() throws Exception {
        // A declined preflight has a finally scope but has issued no native push.
        Field states = ModelStateBatchBridge.class.getDeclaredField("STATES");
        states.setAccessible(true);
        Object state = ((ThreadLocal<?>) states.get(null)).get();
        Field opened = state.getClass().getDeclaredField("scopeOpened");
        opened.setAccessible(true);
        opened.setBoolean(state, true);
        assertFalse(ModelStateBatchBridge.draw(9));
        ModelStateBatchBridge.end();
        assertFalse(opened.getBoolean(state));
        ModelStateBatchBridge.end();
    }

    @Test
    public void bipedRootsUseDrawOrderWithHeadwearLastInsteadOfConstructionOrder() {
        ModelBiped model = new ModelBiped();
        assertSame(model.bipedHead, ModelStateBatchBridge.rootAt(model, 1, 0));
        assertSame(model.bipedBody, ModelStateBatchBridge.rootAt(model, 1, 1));
        assertSame(model.bipedRightArm, ModelStateBatchBridge.rootAt(model, 1, 2));
        assertSame(model.bipedLeftArm, ModelStateBatchBridge.rootAt(model, 1, 3));
        assertSame(model.bipedRightLeg, ModelStateBatchBridge.rootAt(model, 1, 4));
        assertSame(model.bipedLeftLeg, ModelStateBatchBridge.rootAt(model, 1, 5));
        assertSame(model.bipedHeadwear, ModelStateBatchBridge.rootAt(model, 1, 6));
        assertSame(model.bipedHeadwear, model.boxList.get(1));
    }

    @Test
    public void quadrupedRootsUseBothChildAndAdultDrawOrder() {
        ModelQuadruped model = new ModelQuadruped(12, 0.0F);
        assertSame(model.head, ModelStateBatchBridge.rootAt(model, 2, 0));
        assertSame(model.body, ModelStateBatchBridge.rootAt(model, 2, 1));
        assertSame(model.leg1, ModelStateBatchBridge.rootAt(model, 2, 2));
        assertSame(model.leg2, ModelStateBatchBridge.rootAt(model, 2, 3));
        assertSame(model.leg3, ModelStateBatchBridge.rootAt(model, 2, 4));
        assertSame(model.leg4, ModelStateBatchBridge.rootAt(model, 2, 5));
        assertEquals(0, ModelStateBatchBridge.beginRoots(null, 1));
        assertEquals(0, ModelStateBatchBridge.beginRoots(new Object(), 2));
        assertEquals(0, ModelStateBatchBridge.beginRoots(model, 1));
    }
}
