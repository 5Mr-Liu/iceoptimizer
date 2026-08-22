package dev.rlcraft.ice.optimizer.compat.otg;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Result-equivalent parsers for OTG's allocation-heavy configuration hot path. */
public final class OtgParsingBridge {
    private static final Cache<String, String> LOWERCASE_FUNCTION_NAMES =
        Caffeine.newBuilder().maximumSize(128L).build();
    private static volatile Locale lowercaseLocale = Locale.getDefault();

    private OtgParsingBridge() {
    }

    public static String[] readCommaSeparatedString(String line) {
        if (!enabled()) return originalCommaSeparatedString(line);
        try {
            return fastCommaSeparatedString(line);
        } catch (LinkageError | RuntimeException error) {
            OptimizerRegistry.breaker(OptimizationModule.OTG_CONFIG_PARSER).recordFailure(error);
            return originalCommaSeparatedString(line);
        }
    }

    public static String lowercaseFunctionName(String name) {
        if (!enabled()) return name.toLowerCase();
        try {
            Locale current = Locale.getDefault();
            refreshLocale(current);
            String cached = LOWERCASE_FUNCTION_NAMES.getIfPresent(name);
            if (cached != null) return cached;
            String lowered = name.toLowerCase();
            if (Locale.getDefault().equals(current)) LOWERCASE_FUNCTION_NAMES.put(name, lowered);
            return lowered;
        } catch (LinkageError | RuntimeException error) {
            OptimizerRegistry.breaker(OptimizationModule.OTG_CONFIG_PARSER).recordFailure(error);
            return name.toLowerCase();
        }
    }

    static String[] fastCommaSeparatedString(String line) {
        if (line.trim().isEmpty()) return new String[0];
        int depth = 0;
        int values = 1;
        int length = line.length();
        for (int i = 0; i < length; i++) {
            char value = line.charAt(i);
            if (value == ',' && depth == 0) values++;
            if (value == '(') depth++;
            if (value == ')') depth--;
        }
        if (depth != 0) return new String[0];

        String[] result = new String[values];
        int resultIndex = 0;
        int lastFound = 0;
        depth = 0;
        for (int i = 0; i < length; i++) {
            char value = line.charAt(i);
            if (value == ',' && depth == 0) {
                result[resultIndex++] = line.substring(lastFound, i).trim();
                lastFound = i + 1;
            }
            if (value == '(') depth++;
            if (value == ')') depth--;
        }
        result[resultIndex] = line.substring(lastFound, length).trim();
        return result;
    }

    static String[] originalCommaSeparatedString(String line) {
        if (line.trim().isEmpty()) return new String[0];
        List<String> buffer = new LinkedList<String>();
        int index = 0;
        int lastFound = 0;
        int inBracer = 0;
        for (char value : line.toCharArray()) {
            if (value == ',' && inBracer == 0) {
                buffer.add(line.substring(lastFound, index).trim());
                lastFound = index + 1;
            }
            if (value == '(') inBracer++;
            if (value == ')') inBracer--;
            index++;
        }
        buffer.add(line.substring(lastFound, index).trim());
        if (inBracer != 0) return new String[0];
        return buffer.toArray(new String[0]);
    }

    private static boolean enabled() {
        try {
            return OptimizerRegistry.isOperational(OptimizationModule.OTG_CONFIG_PARSER);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    private static void refreshLocale(Locale current) {
        if (current.equals(lowercaseLocale)) return;
        synchronized (LOWERCASE_FUNCTION_NAMES) {
            if (!current.equals(lowercaseLocale)) {
                LOWERCASE_FUNCTION_NAMES.invalidateAll();
                lowercaseLocale = current;
            }
        }
    }
}
