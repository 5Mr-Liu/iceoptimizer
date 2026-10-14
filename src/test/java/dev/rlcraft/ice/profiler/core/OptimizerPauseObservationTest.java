package dev.rlcraft.ice.profiler.core;

import static org.junit.Assert.assertEquals;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

public class OptimizerPauseObservationTest {
    @Test public void recorderFailureCannotInterruptPauseOrResume() {
        boolean original = OptimizerRegistry.isRuntimePaused();
        OptimizerPauseObservation observation = new OptimizerPauseObservation();
        final List<Boolean> changes = new ArrayList<Boolean>();
        try {
            OptimizerRegistry.setRuntimePaused(false);
            observation.connect(new Consumer<Boolean>() {
                @Override public void accept(Boolean value) {
                    changes.add(value);
                    throw new IllegalStateException("recorder failure");
                }
            });
            OptimizerRegistry.setRuntimePaused(true);
            assertEquals(true, OptimizerRegistry.isRuntimePaused());
            OptimizerRegistry.setRuntimePaused(false);
            assertEquals(false, OptimizerRegistry.isRuntimePaused());
            assertEquals(Arrays.asList(false, true, false), changes);
        } finally {
            observation.disconnect();
            OptimizerRegistry.setRuntimePaused(original);
        }
    }

    @Test public void optionalSubscriptionReceivesInitialStateAndChangesWithoutPolling() {
        boolean original = OptimizerRegistry.isRuntimePaused();
        OptimizerPauseObservation observation = new OptimizerPauseObservation();
        final List<Boolean> changes = new ArrayList<Boolean>();
        try {
            OptimizerRegistry.setRuntimePaused(false);
            observation.connect(new Consumer<Boolean>() {
                @Override public void accept(Boolean value) { changes.add(value); }
            });
            OptimizerRegistry.setRuntimePaused(true);
            OptimizerRegistry.setRuntimePaused(true);
            OptimizerRegistry.setRuntimePaused(false);
            assertEquals(Arrays.asList(false, true, false), changes);
            observation.disconnect();
            OptimizerRegistry.setRuntimePaused(true);
            assertEquals(3, changes.size());
        } finally {
            observation.disconnect();
            OptimizerRegistry.setRuntimePaused(original);
        }
    }
}
