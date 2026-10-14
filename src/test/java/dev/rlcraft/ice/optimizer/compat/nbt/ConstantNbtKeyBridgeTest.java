package dev.rlcraft.ice.optimizer.compat.nbt;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.junit.Test;

public class ConstantNbtKeyBridgeTest {
    @Test public void literalKeepsPoolCanonicalIdentityAndNewPoolStartsFresh() {
        OptimizerRegistry.breaker(OptimizationModule.NBT_CONSTANT_KEYS).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.NBT_CONSTANT_KEYS).patchInstalled("test.pool", "test");
        try {
            ObjectOpenHashSet<String> pool = new ObjectOpenHashSet<String>();
            String initial = new String("Blocks"); pool.add(initial);
            String[] cache = new String[6];
            assertSame(initial, ConstantNbtKeyBridge.addOrGet(pool, "Blocks", cache));
            assertSame(initial, ConstantNbtKeyBridge.addOrGet(pool, "Blocks", cache));
            assertSame(initial, ConstantNbtKeyBridge.addOrGet(pool, new String("Blocks"), cache));
            String dynamic = new String("dynamic");
            assertSame(dynamic, ConstantNbtKeyBridge.addOrGet(pool, dynamic, cache));
            assertEquals(2, pool.size());
            ObjectOpenHashSet<String> replacement = new ObjectOpenHashSet<String>();
            assertSame("Blocks", ConstantNbtKeyBridge.addOrGet(replacement, "Blocks", new String[6]));
            OptimizerRegistry.breaker(OptimizationModule.NBT_CONSTANT_KEYS).configure(false, 3);
            assertSame(initial, ConstantNbtKeyBridge.addOrGet(pool, "Blocks", cache));
        } finally { OptimizerRegistry.breaker(OptimizationModule.NBT_CONSTANT_KEYS).configure(false, 3); }
    }
}
