package dev.rlcraft.ice.optimizer.lock;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ClassFingerprintTest {
    @Test
    public void computesCanonicalSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ClassFingerprint.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("ba7816bf8f01cfea", ClassFingerprint.shortSha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
