package dev.rlcraft.ice.hooks;

import net.minecraft.launchwrapper.IClassTransformer;

/** Compatibility name retained for tests and old development tooling. */
@Deprecated
public final class IceClientOptimizerTransformer implements IClassTransformer {
    private final IceOptimizerTransformer delegate = new IceOptimizerTransformer();

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        return delegate.transform(name, transformedName, basicClass);
    }
}
