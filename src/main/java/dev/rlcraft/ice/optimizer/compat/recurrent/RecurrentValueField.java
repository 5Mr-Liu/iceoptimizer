package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import ivorius.ivtoolkit.random.BlurrablePivot;
import ivorius.ivtoolkit.random.BlurredValueField;
import java.util.ArrayList;

/** Ruins-owned fields keep IvToolkit's interpolation, reusing only temporary storage. */
public final class RecurrentValueField extends BlurredValueField {
    private static final int MODULE = OptimizationModule.RECURRENT_VALUE_WORKSPACE.ordinal();
    private static final ThreadLocal<WorkspacePool> WORKSPACES = new ThreadLocal<WorkspacePool>() {
        @Override protected WorkspacePool initialValue() { return new WorkspacePool(); }
    };

    public RecurrentValueField() { super(); }
    public RecurrentValueField(int... size) { super(size); }

    @Override public double getValue(int... position) {
        if (!OptimizerBridge.isEnabled(MODULE)) return super.getValue(position);
        WorkspacePool pool = WORKSPACES.get();
        Workspace workspace = pool.acquire();
        try {
            // Keep the original recursive candidate traversal and its order.
            super.addRelevantValues(position, workspace.relevant);
            int size = workspace.relevant.size();
            if (workspace.inverseDistances.length < size)
                workspace.inverseDistances = new double[size];
            double[] inverseDistances = workspace.inverseDistances;
            double total = 0;
            for (int index = 0; index < size; index++) {
                BlurrablePivot value = workspace.relevant.get(index);
                double distance = 0;
                for (int dimension = 0; dimension < position.length; dimension++) {
                    // IvToolkit subtracts as int before widening, including overflow.
                    double delta = position[dimension] - value.pos()[dimension];
                    distance += delta * delta;
                }
                distance *= distance;
                distance *= distance;
                if (distance <= 0.0001) return value.value();
                inverseDistances[index] = (1.0 / distance) * value.weight();
                total += inverseDistances[index];
            }
            double result = 0;
            total = 1.0 / total;
            for (int index = 0; index < size; index++) {
                // Preserve the two multiplications and sum in the original order.
                result += workspace.relevant.get(index).value() * inverseDistances[index] * total;
            }
            return result;
        } finally {
            pool.release(workspace);
        }
    }

    private static final class Workspace {
        final ArrayList<BlurrablePivot> relevant = new ArrayList<BlurrablePivot>();
        double[] inverseDistances = new double[0];
        Workspace next;
    }

    /** A borrowed frame is absent from the free list, so nested queries own their storage. */
    private static final class WorkspacePool {
        private Workspace free;

        Workspace acquire() {
            Workspace result = free;
            if (result == null) return new Workspace();
            free = result.next;
            result.next = null;
            return result;
        }

        void release(Workspace workspace) {
            workspace.relevant.clear();
            workspace.next = free;
            free = workspace;
        }
    }
}
