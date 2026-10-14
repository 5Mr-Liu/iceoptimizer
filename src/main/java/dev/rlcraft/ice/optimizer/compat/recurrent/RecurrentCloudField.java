package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import ivorius.ivtoolkit.random.BlurredValueField;

/** Created only by buildCloud; the adapter calls this after its last addValue. */
public final class RecurrentCloudField extends BlurredValueField {
    private static final int MODULE = OptimizationModule.RECURRENT_CLOUD_FIELD.ordinal();
    private int[] xs = new int[256], ys = new int[256], zs = new int[256];
    private double[] samples = new double[256];
    private boolean[] occupied = new boolean[256];
    private final int[] position = new int[3];
    private int count;

    public RecurrentCloudField(int... size) { super(size); }

    public double ice$getCloudValue(int x, int y, int z) {
        if (!OptimizerBridge.isEnabled(MODULE)) return super.getValue(x, y, z);
        int slot = slot(x, y, z);
        if (occupied[slot]) return samples[slot];
        position[0] = x;
        position[1] = y;
        position[2] = z;
        double value = super.getValue(position);
        if ((count + 1) * 2 > occupied.length) {
            grow();
            slot = slot(x, y, z);
        }
        xs[slot] = x;
        ys[slot] = y;
        zs[slot] = z;
        samples[slot] = value;
        occupied[slot] = true;
        count++;
        return value;
    }

    private int slot(int x, int y, int z) {
        int hash = x * 0x8da6b343 ^ y * 0xd8163841 ^ z * 0xcb1ab31f;
        hash ^= hash >>> 16;
        int mask = occupied.length - 1;
        int slot = hash & mask;
        while (occupied[slot] && (xs[slot] != x || ys[slot] != y || zs[slot] != z)) slot = (slot + 1) & mask;
        return slot;
    }

    private void grow() {
        int[] oldX = xs, oldY = ys, oldZ = zs;
        double[] oldSamples = samples;
        boolean[] oldOccupied = occupied;
        int capacity = occupied.length * 2;
        xs = new int[capacity]; ys = new int[capacity]; zs = new int[capacity];
        samples = new double[capacity]; occupied = new boolean[capacity];
        for (int i = 0; i < oldOccupied.length; i++) {
            if (!oldOccupied[i]) continue;
            int slot = slot(oldX[i], oldY[i], oldZ[i]);
            xs[slot] = oldX[i]; ys[slot] = oldY[i]; zs[slot] = oldZ[i];
            samples[slot] = oldSamples[i]; occupied[slot] = true;
        }
    }
}
