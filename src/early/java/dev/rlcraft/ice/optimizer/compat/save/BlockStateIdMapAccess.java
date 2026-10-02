package dev.rlcraft.ice.optimizer.compat.save;

import java.util.IdentityHashMap;
import net.minecraft.block.state.IBlockState;

/** Direct entries only: missing states still use Forge's original metadata callbacks. */
public interface BlockStateIdMapAccess {
    IdentityHashMap<IBlockState, Integer> ice$directStateIds();
}
