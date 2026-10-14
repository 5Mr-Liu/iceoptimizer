package dev.rlcraft.ice.optimizer.compat.events;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/** Invocation-owned snapshots retain callback ordering and release every captured object. */
public final class ServerSnapshotBridge {
    private static final ThreadLocal<Frames> FRAMES = new ThreadLocal<Frames>() {
        @Override protected Frames initialValue() { return new Frames(); }
    };
    private ServerSnapshotBridge() { }
    public static boolean levelUpEnabled() {
        return OptimizerBridge.isEnabled(OptimizationModule.LEVELUP_PROCESSOR_SNAPSHOT.ordinal());
    }
    public static boolean radiationEnabled() {
        return OptimizerBridge.isEnabled(OptimizationModule.NUCLEARCRAFT_RADIATION_SNAPSHOT.ordinal());
    }
    public static void begin() { FRAMES.get().push(); }
    public static void end() { FRAMES.get().pop(); }

    /** All has/get callbacks finish before the original method invokes any processor. */
    public static List<Object> processors(List<?> tiles, ServerProcessorAccess callbacks) {
        Frame frame = FRAMES.get().current();
        frame.callbacks = callbacks;
        // Match Stream.collect's bulk traversal and its ConcurrentModificationException timing.
        try { tiles.spliterator().forEachRemaining(frame); }
        finally { frame.callbacks = null; }
        return frame.processors;
    }

    /** Arrays retain their exact original length, including empty entity sections. */
    public static Object[] snapshot(Collection<?> source, int size, Class<?> component, int slot) {
        if (slot == 0 && source instanceof TrackedLoadedChunkMap.Values) {
            return ((TrackedLoadedChunkMap.Values) source).orderedSnapshot(size);
        }
        Frame frame = FRAMES.get().current();
        Object[] values = frame.arrays[slot];
        if (values == null || values.length != size || values.getClass().getComponentType() != component) {
            values = (Object[]) Array.newInstance(component, size);
        } else {
            Arrays.fill(values, null);
        }
        values = source.toArray(values);
        frame.arrays[slot] = values;
        return values;
    }

    private static final class Frames {
        final ArrayList<Frame> frames = new ArrayList<Frame>(2);
        int depth;
        void push() { if (depth == frames.size()) frames.add(new Frame()); depth++; }
        Frame current() { return frames.get(depth - 1); }
        void pop() {
            Frame frame = frames.get(--depth);
            frame.processors.clear();
            for (Object[] values : frame.arrays) if (values != null) Arrays.fill(values, null);
        }
    }
    private static final class Frame implements Consumer<Object> {
        final ArrayList<Object> processors = new ArrayList<Object>();
        final Object[][] arrays = new Object[3][];
        ServerProcessorAccess callbacks;
        @Override public void accept(Object tile) {
            if (callbacks.ice$hasProcessor(tile)) processors.add(callbacks.ice$getProcessor(tile));
        }
    }
}
