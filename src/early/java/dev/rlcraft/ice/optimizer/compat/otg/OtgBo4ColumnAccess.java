package dev.rlcraft.ice.optimizer.compat.otg;

/** Injected direct field access; -1 denotes a block outside BO4's 16 by 16 columns. */
public interface OtgBo4ColumnAccess {
    int ice$column(Object block);
}
