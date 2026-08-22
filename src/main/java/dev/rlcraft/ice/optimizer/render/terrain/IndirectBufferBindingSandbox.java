package dev.rlcraft.ice.optimizer.render.terrain;

/**
 * Authenticates and restores GL_DRAW_INDIRECT_BUFFER without guessing an
 * unknown compatibility binding.  The access seam keeps the outcome-uncertain
 * restore path testable without an OpenGL context.
 */
final class IndirectBufferBindingSandbox {
    static final int UNKNOWN = Integer.MIN_VALUE;

    interface Access {
        int trackedBinding();
        int queryBinding();
        void publishBinding(int nativeId);
        void bind(int nativeId);
        void invalidate();
    }

    static Lease acquire(Access access) {
        if (access == null) throw new IllegalArgumentException("indirect binding access");
        int previous = access.trackedBinding();
        boolean queried = false;
        if (previous == UNKNOWN) {
            queried = true;
            try {
                previous = access.queryBinding();
                if (previous < 0) {
                    throw new IllegalStateException(
                        "negative GL_DRAW_INDIRECT_BUFFER binding");
                }
                access.publishBinding(previous);
            } catch (Throwable failure) {
                try { access.invalidate(); }
                catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                rethrow(failure);
            }
        }
        return new Lease(access, previous, queried);
    }

    static final class Lease {
        private final Access access;
        private final int previous;
        private final boolean queried;
        private boolean touched;
        private boolean restored;

        private Lease(Access access, int previous, boolean queried) {
            this.access = access;
            this.previous = previous;
            this.queried = queried;
        }

        int previous() { return previous; }
        boolean queried() { return queried; }

        void bind(int nativeId) {
            if (restored || nativeId < 0) {
                throw new IllegalStateException("indirect binding lease");
            }
            access.bind(nativeId);
            touched = true;
        }

        void restore() {
            if (restored) return;
            restored = true;
            if (!touched) return;
            try {
                access.bind(previous);
            } catch (Throwable failure) {
                try { access.invalidate(); }
                catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                rethrow(failure);
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("indirect binding sandbox failed", failure);
    }
}
