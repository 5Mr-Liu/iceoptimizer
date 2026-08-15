package dev.rlcraft.ice.optimizer;

import dev.rlcraft.ice.optimizer.memory.BudgetedCache;
import dev.rlcraft.ice.optimizer.memory.CompressedByteStore;
import dev.rlcraft.ice.optimizer.memory.FrameMemoTable;
import dev.rlcraft.ice.optimizer.memory.WeightedValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Executed separately with only the final shaded JAR on the classpath. */
public final class ShadedRuntimeSmokeMain {
    private ShadedRuntimeSmokeMain() {
    }

    public static void main(String[] args) {
        byte[] source = "private relocated LZ4 payload".getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(source, CompressedByteStore.compress(source).restore())) throw new AssertionError("LZ4 relocation failed");
        FrameMemoTable<String> memo = new FrameMemoTable<String>(16);
        if (!"ok".equals(memo.getOrCompute(1L, 2L, key -> "ok"))) throw new AssertionError("Agrona relocation failed");
        BudgetedCache<String, Value> cache = new BudgetedCache<String, Value>(1024L, null);
        cache.put("key", new Value());
        if (cache.getIfPresent("key") == null) throw new AssertionError("Caffeine relocation failed");
        System.out.println("SHADED_RUNTIME_OK");
    }

    private static final class Value implements WeightedValue {
        @Override public int weightBytes() { return 32; }
    }
}
