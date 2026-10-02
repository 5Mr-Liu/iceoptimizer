package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.Test;

public final class OtgSettingsSnapshotTest {
    @Test public void multibyteAndLongLinesHaveExactByteCountLineNumbersAndOrdering() throws Exception {
        StringBuilder source = new StringBuilder("# comment\r\nI: 初始\r\nI=最后\n");
        for (int index = 0; index < 12000; index++) source.append('界');
        source.append("(1,2)\nFunction(\"A:B\")\n");
        byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
        OtgSettingsSnapshot.ReadResult result = OtgSettingsSnapshot.readAndClose(
            new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, new Locale("tr", "TR"), bytes.length);
        assertEquals(bytes.length, result.bytes);
        assertEquals("ı", result.snapshot.settings.get(0).key);
        assertEquals("ı", result.snapshot.settings.get(1).key);
        assertEquals("最后", result.snapshot.settings.get(1).value);
        assertEquals(3, result.snapshot.settings.get(1).line);
        assertEquals(4, result.snapshot.functions.get(0).line);
        assertEquals("Function(\"A:B\")", result.snapshot.functions.get(1).value);
    }
    @Test public void sourceLimitIncludesBytesReadDuringDecodingAndClosesTheInput() throws Exception {
        final boolean[] closed = { false };
        ByteArrayInputStream input = new ByteArrayInputStream("Value: too-long\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed[0] = true; }
        };
        try {
            OtgSettingsSnapshot.readAndClose(input, StandardCharsets.UTF_8, Locale.US, 4);
            fail("oversized stream was accepted");
        } catch (IOException expected) { assertTrue(closed[0]); }
    }
    @Test public void closeFailureCannotReturnAParsedSnapshot() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream("Value: parsed\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { throw new IOException("close failed"); }
        };
        try {
            OtgSettingsSnapshot.readAndClose(input, StandardCharsets.UTF_8, Locale.US, 1024);
            fail("snapshot escaped despite source close failure");
        } catch (IOException expected) { assertEquals("close failed", expected.getMessage()); }
    }
}
