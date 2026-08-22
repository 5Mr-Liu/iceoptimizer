package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.LinkedHashMap;

/** Safe subset-compatible Java-properties parser with hard input/cardinality limits. */
public final class ShaderPackPropertiesParser {
    private final int maximumBytes;
    private final int maximumEntries;
    private final int maximumPermutationDirectives;
    private final int maximumKeyChars;
    private final int maximumValueChars;
    private final int maximumContinuations;

    public ShaderPackPropertiesParser() {
        this(1024 * 1024, 4096, 2048, 512, 65536, 16);
    }

    public ShaderPackPropertiesParser(int maximumBytes, int maximumEntries,
                                      int maximumPermutationDirectives,
                                      int maximumKeyChars,
                                      int maximumValueChars,
                                      int maximumContinuations) {
        this.maximumBytes = Math.max(1024, maximumBytes);
        this.maximumEntries = Math.max(16, maximumEntries);
        this.maximumPermutationDirectives = Math.max(16,
            maximumPermutationDirectives);
        this.maximumKeyChars = Math.max(16, maximumKeyChars);
        this.maximumValueChars = Math.max(64, maximumValueChars);
        this.maximumContinuations = Math.max(1, maximumContinuations);
    }

    public ShaderPackProperties parse(String source) {
        if (source == null) throw new IllegalArgumentException("shader properties");
        if (source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("NUL in shader properties");
        }
        if (boundedUtf8Length(source, maximumBytes) > maximumBytes) {
            throw new IllegalArgumentException("shader properties byte limit exceeded");
        }
        LinkedHashMap<String, String> values =
            new LinkedHashMap<String, String>();
        int permutationDirectives = 0;
        int offset = 0;
        while (offset <= source.length()) {
            LogicalLine logical = logicalLine(source, offset);
            offset = logical.nextOffset;
            String line = logical.value;
            int start = skipWhitespace(line, 0);
            if (start < line.length() && line.charAt(start) != '#'
                && line.charAt(start) != '!') {
                Pair pair = split(line, start);
                String key = unescape(pair.key);
                String value = unescape(pair.value);
                validateUnicode(key);
                validateUnicode(value);
                if (key.isEmpty() || key.length() > maximumKeyChars) {
                    throw new IllegalArgumentException("invalid shader property key");
                }
                if (value.length() > maximumValueChars) {
                    throw new IllegalArgumentException("shader property value too large");
                }
                boolean fresh = !values.containsKey(key);
                if (fresh && values.size() >= maximumEntries) {
                    throw new IllegalArgumentException("shader property count exceeded");
                }
                if (fresh && isPermutationDirective(key)) {
                    if (++permutationDirectives > maximumPermutationDirectives) {
                        throw new IllegalArgumentException(
                            "shader permutation directive limit exceeded");
                    }
                }
                values.put(key, value);
            }
            if (offset >= source.length()) break;
        }
        return new ShaderPackProperties(values, permutationDirectives);
    }

    private LogicalLine logicalLine(String source, int offset) {
        StringBuilder result = new StringBuilder();
        int continuations = 0;
        int cursor = offset;
        while (true) {
            int end = cursor;
            while (end < source.length() && source.charAt(end) != '\n'
                && source.charAt(end) != '\r') end++;
            result.append(source, cursor, end);
            int next = end;
            if (next < source.length() && source.charAt(next) == '\r') next++;
            if (next < source.length() && source.charAt(next) == '\n') next++;
            int slashes = trailingBackslashes(result);
            if ((slashes & 1) == 0) return new LogicalLine(result.toString(), next);
            if (++continuations > maximumContinuations) {
                throw new IllegalArgumentException("shader property continuation limit exceeded");
            }
            result.setLength(result.length() - 1);
            cursor = skipWhitespace(source, next);
            if (cursor >= source.length()) {
                return new LogicalLine(result.toString(), source.length());
            }
        }
    }

    private static Pair split(String line, int start) {
        boolean escaped = false;
        int separator = -1;
        int valueStart = line.length();
        for (int index = start; index < line.length(); index++) {
            char value = line.charAt(index);
            if (!escaped && (value == '=' || value == ':'
                || isPropertyWhitespace(value))) {
                separator = index;
                valueStart = index;
                break;
            }
            if (value == '\\' && !escaped) escaped = true;
            else escaped = false;
        }
        if (separator < 0) return new Pair(line.substring(start), "");
        int cursor = valueStart;
        while (cursor < line.length()
            && isPropertyWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        if (cursor < line.length()
            && (line.charAt(cursor) == '=' || line.charAt(cursor) == ':')) cursor++;
        cursor = skipWhitespace(line, cursor);
        return new Pair(line.substring(start, separator), line.substring(cursor));
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                result.append(current);
                continue;
            }
            if (++index >= value.length()) {
                throw new IllegalArgumentException("trailing shader property escape");
            }
            char escaped = value.charAt(index);
            if (escaped == 't') result.append('\t');
            else if (escaped == 'r') result.append('\r');
            else if (escaped == 'n') result.append('\n');
            else if (escaped == 'f') result.append('\f');
            else if (escaped == 'u') {
                if (index + 4 >= value.length()) {
                    throw new IllegalArgumentException("short unicode escape");
                }
                int code = 0;
                for (int digit = 0; digit < 4; digit++) {
                    int hex = Character.digit(value.charAt(++index), 16);
                    if (hex < 0) throw new IllegalArgumentException(
                        "invalid unicode escape");
                    code = (code << 4) | hex;
                }
                if (code == 0) throw new IllegalArgumentException(
                    "NUL unicode escape in shader properties");
                result.append((char) code);
            } else result.append(escaped);
        }
        return result.toString();
    }

    private static int trailingBackslashes(StringBuilder value) {
        int count = 0;
        for (int index = value.length() - 1;
             index >= 0 && value.charAt(index) == '\\'; index--) count++;
        return count;
    }

    /** Escaped UTF-16 must remain scalar-valid just like the original input. */
    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                        "invalid Unicode escape in shader properties");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                    "invalid Unicode escape in shader properties");
            }
        }
    }

    private static int skipWhitespace(String value, int offset) {
        int cursor = Math.max(0, offset);
        while (cursor < value.length()
            && isPropertyWhitespace(value.charAt(cursor))) cursor++;
        return cursor;
    }

    /** java.util.Properties recognizes only space, tab and form-feed here. */
    private static boolean isPropertyWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\f';
    }

    private static boolean isPermutationDirective(String key) {
        return key.startsWith("program.") || key.startsWith("profile.")
            || key.startsWith("screen.") || key.startsWith("variable.");
    }

    private static int boundedUtf8Length(String value, int maximum) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int added;
            if (current <= 0x7f) added = 1;
            else if (current <= 0x7ff) added = 2;
            else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                        "invalid Unicode in shader properties");
                }
                index++;
                added = 4;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                    "invalid Unicode in shader properties");
            } else added = 3;
            if (added > maximum - bytes) {
                throw new IllegalArgumentException(
                    "shader properties byte limit exceeded");
            }
            bytes += added;
        }
        return bytes;
    }

    private static final class LogicalLine {
        private final String value;
        private final int nextOffset;
        private LogicalLine(String value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    private static final class Pair {
        private final String key;
        private final String value;
        private Pair(String key, String value) { this.key = key; this.value = value; }
    }
}
