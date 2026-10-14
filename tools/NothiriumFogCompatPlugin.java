package dev.rlcraft.ice.benchmark.compat;

import java.io.File;
import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("ICE isolated Nothirium fog experiment")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(2002)
@IFMLLoadingPlugin.TransformerExclusions({"dev.rlcraft.ice.benchmark.compat."})
public final class NothiriumFogCompatPlugin implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() {
        return new String[] {NothiriumFogCompatTransformer.class.getName()};
    }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public String getAccessTransformerClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {
        Object game = data.get("mcLocation");
        if (!(game instanceof File) || !new File((File) game, "ICE_ISOLATED_BENCHMARK.marker").isFile())
            throw new IllegalStateException("Experimental fog bridge requires an isolated benchmark");
    }
}
