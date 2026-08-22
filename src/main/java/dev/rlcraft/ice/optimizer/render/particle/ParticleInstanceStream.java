package dev.rlcraft.ice.optimizer.render.particle;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact-list-order primitive instance stream.  Production recording allocates
 * no per-particle objects; immutable views are materialized only for tests or
 * explicit diagnostics through flush().
 */
public final class ParticleInstanceStream implements AutoCloseable {
    private final int capacity;
    private float[] x;
    private float[] y;
    private float[] z;
    private float[] scale;
    private float[] rotation;
    private int[] rgba;
    private int[] packedLight;
    private float[] minU;
    private float[] minV;
    private float[] maxU;
    private float[] maxV;
    private long[] sequence;
    private ParticleState[] runStates;
    private int[] runStarts;
    private int[] runCounts;
    private CacheBudget.Reservation heapReservation;
    private long lastSequence = -1L;
    private int instanceCount;
    private int runCount;
    private int activeRun = -1;
    private long rejected;
    private long barriers;

    public ParticleInstanceStream(int capacity) {
        this(capacity, null);
    }

    /** Production constructor whose retained primitive/reference arrays are budgeted. */
    public ParticleInstanceStream(int capacity, CacheBudget budget) {
        this.capacity = Math.max(64, capacity);
        CacheBudget.Reservation reservation = RetainedHeap.reserve(budget,
            heapBytesForCapacity(this.capacity), "particle instance stream");
        try {
            this.x = new float[this.capacity];
            this.y = new float[this.capacity];
            this.z = new float[this.capacity];
            this.scale = new float[this.capacity];
            this.rotation = new float[this.capacity];
            this.rgba = new int[this.capacity];
            this.packedLight = new int[this.capacity];
            this.minU = new float[this.capacity];
            this.minV = new float[this.capacity];
            this.maxU = new float[this.capacity];
            this.maxV = new float[this.capacity];
            this.sequence = new long[this.capacity];
            this.runStates = new ParticleState[this.capacity];
            this.runStarts = new int[this.capacity];
            this.runCounts = new int[this.capacity];
            this.heapReservation = reservation;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    public boolean record(ParticleState state, ParticleInstance instance) {
        return instance != null && record(state, instance.getX(), instance.getY(),
            instance.getZ(), instance.getScale(), instance.getRotation(),
            instance.getRgba(), instance.getPackedLight(), instance.getMinU(),
            instance.getMinV(), instance.getMaxU(), instance.getMaxV(),
            instance.getSequence());
    }

    public boolean record(ParticleState state, float particleX, float particleY,
                          float particleZ, float particleScale,
                          float particleRotation, int particleRgba,
                          int particleLight, float particleMinU,
                          float particleMinV, float particleMaxU,
                          float particleMaxV, long particleSequence) {
        checkOpen();
        if (state == null || !canRecord(particleSequence)) {
            rejected++;
            return false;
        }
        if (activeRun < 0 || !runStates[activeRun].equals(state)) {
            if (runCount >= runStates.length) {
                rejected++;
                return false;
            }
            activeRun = runCount++;
            runStates[activeRun] = state;
            runStarts[activeRun] = instanceCount;
            runCounts[activeRun] = 0;
        }
        int index = instanceCount++;
        x[index] = particleX;
        y[index] = particleY;
        z[index] = particleZ;
        scale[index] = particleScale;
        rotation[index] = particleRotation;
        rgba[index] = particleRgba;
        packedLight[index] = particleLight;
        minU[index] = particleMinU;
        minV[index] = particleMinV;
        maxU[index] = particleMaxU;
        maxV[index] = particleMaxV;
        sequence[index] = particleSequence;
        runCounts[activeRun]++;
        lastSequence = particleSequence;
        return true;
    }

    public boolean canRecord(long particleSequence) {
        checkOpen();
        return particleSequence >= 0L && particleSequence >= lastSequence
            && instanceCount < capacity;
    }

    /** Closes a run at an observable/FBP boundary without copying data. */
    public void barrier() {
        checkOpen();
        activeRun = -1;
        barriers++;
    }

    public List<ParticleBatch> flush() {
        checkOpen();
        ArrayList<ParticleBatch> result = new ArrayList<ParticleBatch>(runCount);
        for (int run = 0; run < runCount; run++) {
            ArrayList<ParticleInstance> instances =
                new ArrayList<ParticleInstance>(runCounts[run]);
            int end = runStarts[run] + runCounts[run];
            for (int index = runStarts[run]; index < end; index++) {
                instances.add(new ParticleInstance(x[index], y[index], z[index],
                    scale[index], rotation[index], rgba[index], packedLight[index],
                    minU[index], minV[index], maxU[index], maxV[index],
                    sequence[index]));
            }
            result.add(new ParticleBatch(runStates[run], instances));
        }
        discardAtBarrier();
        List<ParticleBatch> immutable = Collections.unmodifiableList(result);
        return immutable;
    }

    public void discardAtBarrier() {
        checkOpen();
        for (int run = 0; run < runCount; run++) runStates[run] = null;
        activeRun = -1;
        runCount = 0;
        lastSequence = -1L;
        instanceCount = 0;
    }

    public int size() { return instanceCount; }
    public int runCount() { return runCount; }
    public long getBarriers() { return barriers; }
    public long getRejected() { return rejected; }

    public boolean isClosed() { return x == null; }

    @Override public void close() {
        if (x == null) return;
        for (int run = 0; run < runCount; run++) runStates[run] = null;
        x = null;
        y = null;
        z = null;
        scale = null;
        rotation = null;
        rgba = null;
        packedLight = null;
        minU = null;
        minV = null;
        maxU = null;
        maxV = null;
        sequence = null;
        runStates = null;
        runStarts = null;
        runCounts = null;
        activeRun = -1;
        runCount = 0;
        instanceCount = 0;
        lastSequence = -1L;
        CacheBudget.Reservation reservation = heapReservation;
        heapReservation = null;
        if (reservation != null) reservation.close();
    }

    public static long heapBytesForCapacity(int requestedCapacity) {
        int actual = Math.max(64, requestedCapacity);
        long bytes = Math.multiplyExact(9L, RetainedHeap.floatArray(actual));
        bytes = Math.addExact(bytes,
            Math.multiplyExact(4L, RetainedHeap.intArray(actual)));
        bytes = Math.addExact(bytes, RetainedHeap.longArray(actual));
        return Math.addExact(bytes, RetainedHeap.referenceArray(actual));
    }

    private void checkOpen() {
        if (x == null) throw new IllegalStateException(
            "particle instance stream is closed");
    }
}
