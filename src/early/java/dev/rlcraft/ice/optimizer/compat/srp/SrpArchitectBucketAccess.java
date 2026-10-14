package dev.rlcraft.ice.optimizer.compat.srp;

import java.util.Map;

/** The section collection owns both the live class buckets and their registration. */
public interface SrpArchitectBucketAccess {
    Map<Class<?>, ?> ice$classBuckets();
    Class<?> ice$resolveEntityClass(Class<?> type);
    Class<?> ice$architectClass();
    void ice$architectClass(Class<?> type);
}
