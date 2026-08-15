package dev.rlcraft.ice.optimizer.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizerRuntimeSide;
import java.io.File;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RlcPackLockTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void legacyStrictFlagAndMissingComponentsNeverBlockCapabilityAdapters() throws Exception {
        File gameDirectory = temporary.newFolder("unrestricted-pack");
        PackLockStatus strict = new RlcPackLock().inspect(
            Collections.<net.minecraftforge.fml.common.ModContainer>emptyList(), gameDirectory, true, false);
        PackLockStatus relaxed = new RlcPackLock().inspect(
            Collections.<net.minecraftforge.fml.common.ModContainer>emptyList(), gameDirectory, false, false);
        assertEquals(PackLockState.CAPABILITY, strict.getState());
        assertEquals(PackLockState.CAPABILITY, relaxed.getState());
        assertTrue(strict.permitsPatches());
        assertTrue(strict.getDetail().contains("无整合包版本限制"));
        assertNull(strict.getObservationFile());
        assertFalse(new File(gameDirectory, "ice-optimizer").exists());
    }

    @Test
    public void developmentInventoryIsDiagnosticOnlyAndSeparatedByPhysicalSide() throws Exception {
        File gameDirectory = temporary.newFolder("component-observation");
        PackLockStatus client = new RlcPackLock().inspect(
            Collections.<net.minecraftforge.fml.common.ModContainer>emptyList(), gameDirectory,
            OptimizerRuntimeSide.CLIENT, true, true);
        assertEquals(PackLockState.CAPABILITY, client.getState());
        assertNotNull(client.getObservationFile());
        assertEquals("components-observed.properties", client.getObservationFile().getName());
        assertTrue(client.getObservationFile().isFile());

        PackLockStatus server = new RlcPackLock().inspect(
            Collections.<net.minecraftforge.fml.common.ModContainer>emptyList(), gameDirectory,
            OptimizerRuntimeSide.DEDICATED_SERVER, false, true);
        assertEquals(PackLockState.CAPABILITY, server.getState());
        assertNotNull(server.getObservationFile());
        assertEquals("components-observed-server.properties", server.getObservationFile().getName());
        assertTrue(server.getObservationFile().isFile());
    }
}
