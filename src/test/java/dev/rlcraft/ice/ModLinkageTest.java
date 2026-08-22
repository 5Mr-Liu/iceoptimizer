package dev.rlcraft.ice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.command.CommandIce;
import dev.rlcraft.ice.command.CommandIceClient;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.probe.ProbeBridge;
import dev.rlcraft.ice.server.ServerProfilerController;
import net.minecraftforge.fml.common.Mod;
import org.junit.Test;

public class ModLinkageTest {
    @Test
    public void linksCommonAndClientEntrypointsAgainstForge() {
        assertEquals("iceoptimizer", IceMod.MOD_ID);
        assertEquals("ICE RLCraft Optimizer", IceMod.NAME);
        assertEquals("1.0.5", IceMod.VERSION);
        assertEquals("iceprofiler", IceProfilerMod.MOD_ID);
        assertEquals("ICE Performance Recorder", IceProfilerMod.NAME);
        assertEquals("1.0.5", IceProfilerMod.VERSION);
        assertNotNull(new CommandIce());
        assertNotNull(new CommandIceClient());
        assertNotNull(IceConfig.capture);
        assertNotNull(OptimizerConfig.settings);
        assertNotNull(ProfilerRuntime.INSTANCE);
        assertNotNull(ServerProfilerController.INSTANCE);
        assertTrue(IceConfig.general.maxProfilerMemoryMiB >= 16);
        assertTrue(!ProbeBridge.isEnabled() || IceConfig.probes.deepProfiling);
    }

    @Test
    public void optimizerRequiresTheSameVersionOnBothPhysicalSides() {
        Mod metadata = IceMod.class.getAnnotation(Mod.class);
        assertNotNull(metadata);
        assertEquals("[1.0.5]", metadata.acceptableRemoteVersions());
        assertTrue(!metadata.clientSideOnly());
        assertTrue(!metadata.serverSideOnly());
    }
}
