package dev.rlcraft.ice.optimizer.render.terrain;

import net.minecraft.util.math.BlockPos;
import org.lwjgl.util.vector.Vector3f;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeAnimationOffsetTest {
    public static class Handler {
        final Vector3f value = new Vector3f(1, 2, 3);
        Object received;
        public Vector3f getOffset(Object chunk, BlockPos position) { received = chunk; return position == null ? null : value; }
    }
    @Test public void bindsEachReceiverAndPreservesArgumentsAndNullResult() throws Throwable {
        Handler first = new Handler(), second = new Handler();
        NativeAnimationOffset a = NativeAnimationOffset.bind(first), b = NativeAnimationOffset.bind(second);
        Object chunk = new Object();
        assertSame(first.value, a.get(chunk, BlockPos.ORIGIN));
        assertSame(second.value, b.get(second, BlockPos.ORIGIN));
        assertSame(chunk, first.received); assertSame(second, second.received);
        assertNull(a.get(chunk, null));
    }
}
