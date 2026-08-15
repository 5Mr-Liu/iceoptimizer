package dev.rlcraft.ice.hooks;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("ICE RLCraft Optimizer Core")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(2001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.rlcraft.ice.hooks."})
public final class IceOptimizerLoadingPlugin implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() {
        return new String[] { IceOptimizerTransformer.class.getName() };
    }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { OptimizerDiscovery.initialize(data); }
    @Override public String getAccessTransformerClass() { return null; }
}
