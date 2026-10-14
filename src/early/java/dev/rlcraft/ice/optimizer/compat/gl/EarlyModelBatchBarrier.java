package dev.rlcraft.ice.optimizer.compat.gl;

import java.nio.FloatBuffer;

/** Core-JAR callback: deferred model vertices must precede state mutations. */
public final class EarlyModelBatchBarrier {
    private static volatile boolean active;
    private static final ThreadLocal<Scope> SCOPES = new ThreadLocal<Scope>() {
        @Override protected Scope initialValue() { return new Scope(); }
    };

    private EarlyModelBatchBarrier() { }

    public static void enter(Runnable handler, Runnable synchronizeMatrix) {
        Scope scope = SCOPES.get();
        scope.handler = handler;
        scope.synchronizeMatrix = synchronizeMatrix;
        scope.defer = true;
        scope.dirty = false;
        scope.baseDepth = EarlyMatrixStateTracker.modelViewDepth();
        active = true;
    }

    public static void leave() {
        Scope scope = SCOPES.get();
        scope.handler = scope.synchronizeMatrix = null;
        scope.defer = scope.dirty = false;
        active = false;
    }

    public static void barrier() {
        if (!active) return;
        Scope scope = SCOPES.get();
        if (scope.handler == null || scope.inBarrier) return;
        scope.inBarrier = true;
        try { scope.handler.run(); }
        finally { scope.inBarrier = false; }
    }

    /** Part transforms are captured at draw; projection and texture transforms are not. */
    public static void matrixBarrier() {
        if (active && EarlyMatrixStateTracker.currentMode() != 5888) barrier();
    }

    public static void synchronize() {
        if (!active) return;
        Scope scope = SCOPES.get();
        if (scope.dirty) {
            scope.synchronizeMatrix.run();
            scope.dirty = false;
        }
    }

    public static void matrixInvalidated() {
        if (!active) return;
        Scope scope = SCOPES.get();
        try { barrier(); }
        finally { scope.defer = scope.dirty = false; }
    }

    private static Scope deferred() {
        if (!active || EarlyMatrixStateTracker.currentMode() != 5888) return null;
        Scope scope = SCOPES.get();
        return scope.defer ? scope : null;
    }

    public static boolean deferTranslate(float x, float y, float z) {
        Scope scope = deferred(); if (scope == null) return false;
        EarlyMatrixStateTracker.translate(x, y, z);
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }
    public static boolean deferTranslate(double x, double y, double z) {
        Scope scope = deferred(); if (scope == null) return false;
        EarlyMatrixStateTracker.translate(x, y, z);
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }
    public static boolean deferScale(float x, float y, float z) {
        Scope scope = deferred(); if (scope == null) return false;
        EarlyMatrixStateTracker.scale(x, y, z);
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }
    public static boolean deferScale(double x, double y, double z) {
        Scope scope = deferred(); if (scope == null) return false;
        EarlyMatrixStateTracker.scale(x, y, z);
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }
    public static boolean deferRotate(float angle, float x, float y, float z) {
        Scope scope = deferred(); if (scope == null) return false;
        EarlyMatrixStateTracker.rotate(angle, x, y, z);
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }

    public static boolean deferMultMatrix(FloatBuffer matrix) {
        Scope scope = deferred(); if (scope == null) return false;
        // Preserve LWJGL's invalid-buffer exception before changing the mirror.
        if (matrix == null || !matrix.isDirect() || matrix.remaining() < 16) return false;
        EarlyMatrixStateTracker.multMatrix(matrix);
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }

    public static boolean deferPush() {
        Scope scope = deferred(); if (scope == null) return false;
        EarlyMatrixStateTracker.pushMatrix();
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }

    public static boolean deferPop() {
        Scope scope = deferred(); if (scope == null) return false;
        if (EarlyMatrixStateTracker.modelViewDepth() <= scope.baseDepth) {
            // The owner may close a matrix level opened before this scope.
            // Materialize our levels first, then let the original call own it.
            matrixInvalidated();
            return false;
        }
        EarlyMatrixStateTracker.popMatrix();
        return scope.dirty = EarlyMatrixStateTracker.isModelViewKnown();
    }

    private static final class Scope {
        Runnable handler, synchronizeMatrix;
        int baseDepth;
        boolean dirty, defer, inBarrier;
    }
}
