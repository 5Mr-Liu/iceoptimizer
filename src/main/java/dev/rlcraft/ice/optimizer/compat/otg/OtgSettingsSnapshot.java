package dev.rlcraft.ice.optimizer.compat.otg;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parsed OTG settings only; source bytes are never accumulated or retained. */
final class OtgSettingsSnapshot {
    final List<LineRecord> functions;
    final List<SettingRecord> settings;
    final int weight;

    private OtgSettingsSnapshot(List<LineRecord> functions, List<SettingRecord> settings) {
        // These lists belong exclusively to the parser and no longer mutate.
        this.functions = Collections.unmodifiableList(functions);
        this.settings = Collections.unmodifiableList(settings);
        long bytes = 128L;
        for (LineRecord line : functions) bytes += 48L + (long) line.value.length() * 2L;
        for (SettingRecord setting : settings) {
            bytes += 72L + (long) (setting.key.length() + setting.value.length()) * 2L;
        }
        weight = (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    /** A result can escape only after the source has reached EOF and closed successfully. */
    static ReadResult readAndClose(InputStream input, Charset charset, Locale locale,
                                   int maximumBytes) throws IOException {
        List<LineRecord> functions = new ArrayList<LineRecord>();
        List<SettingRecord> settings = new ArrayList<SettingRecord>();
        CountingInputStream counted = new CountingInputStream(input, maximumBytes);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(counted, charset))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || line.startsWith("#") || line.startsWith("<")) continue;
                int colon = line.indexOf(':');
                int opening = line.indexOf('(');
                if (colon >= 0 || opening >= 0) {
                    if (opening >= 0 && (colon < 0 || opening < colon)) {
                        functions.add(new LineRecord(trimmed, number));
                    } else {
                        settings.add(setting(line, colon, number, locale));
                    }
                } else {
                    int equals = line.indexOf('=');
                    if (equals >= 0) settings.add(setting(line, equals, number, locale));
                }
            }
            return new ReadResult(new OtgSettingsSnapshot(functions, settings), counted.count);
        }
    }

    private static SettingRecord setting(String line, int separator, int number, Locale locale) {
        return new SettingRecord(line.substring(0, separator).trim().toLowerCase(locale),
            line.substring(separator + 1).trim(), number);
    }

    static class LineRecord {
        final String value;
        final int line;
        LineRecord(String value, int line) { this.value = value; this.line = line; }
    }

    static final class SettingRecord extends LineRecord {
        final String key;
        SettingRecord(String key, String value, int line) {
            super(value, line);
            this.key = key;
        }
    }

    static final class ReadResult {
        final OtgSettingsSnapshot snapshot;
        final long bytes;
        ReadResult(OtgSettingsSnapshot snapshot, long bytes) {
            this.snapshot = snapshot;
            this.bytes = bytes;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final int maximum;
        private long count;
        CountingInputStream(InputStream input, int maximum) {
            super(input);
            this.maximum = maximum;
        }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) record(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            if (read > 0) record(read);
            return read;
        }
        private void record(int read) throws IOException {
            count += read;
            if (count > maximum) throw new IOException("OTG settings exceeded the source limit");
        }
    }
}
