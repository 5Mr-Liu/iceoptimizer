package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class ModernRendererDiagnosticsTest {
    @After
    public void reset() {
        ModernRendererDiagnostics.resetForTest();
    }

    @Test
    public void publishesImmutableBoundedStrings() {
        String report = repeat('r', 70000);
        String summary = repeat('s', 700);

        ModernRendererDiagnostics.publish(report, summary);

        assertEquals(65536, ModernRendererDiagnostics.report().length());
        assertEquals(512, ModernRendererDiagnostics.summary().length());
        assertEquals('r', ModernRendererDiagnostics.report().charAt(65535));
        assertEquals('s', ModernRendererDiagnostics.summary().charAt(511));
    }

    @Test
    public void normalizesNullPublicationToEmptyStrings() {
        ModernRendererDiagnostics.publish(null, null);
        assertEquals("", ModernRendererDiagnostics.report());
        assertEquals("", ModernRendererDiagnostics.summary());
    }

    private static String repeat(char value, int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(value);
        return result.toString();
    }
}
