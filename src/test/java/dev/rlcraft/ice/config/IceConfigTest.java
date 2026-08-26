package dev.rlcraft.ice.config;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class IceConfigTest {
    @Test
    public void automaticRecordingIsSilentByDefault() {
        assertTrue(IceConfig.client.silentAutomaticRecording);
    }
}
