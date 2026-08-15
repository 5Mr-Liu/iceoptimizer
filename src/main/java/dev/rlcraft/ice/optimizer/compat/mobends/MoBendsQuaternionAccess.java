package dev.rlcraft.ice.optimizer.compat.mobends;

import java.nio.FloatBuffer;

/** Optional ABI injected into the exact reviewed Mo' Bends Quaternion class. */
public interface MoBendsQuaternionAccess {
    FloatBuffer rlcraftIce$getGlMatrix();
}
