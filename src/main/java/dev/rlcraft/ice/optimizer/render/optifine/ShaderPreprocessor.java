package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded include/macro parser with strict pack-root confinement. */
public final class ShaderPreprocessor {
    private static final Pattern INCLUDE = Pattern.compile(
        "^\\s*#\\s*include\\s*[\\\"<]([^\\\">]+)[\\\">]\\s*$");
    private static final Pattern DEFINE = Pattern.compile(
        "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*))?$");
    private final int maximumDepth;
    private final int maximumIncludes;
    private final int maximumMacros;
    private final int maximumInputBytes;
    private final int maximumExpandedBytes;

    public ShaderPreprocessor() {
        this(32, 512, 4096, 1024 * 1024, 4 * 1024 * 1024);
    }

    public ShaderPreprocessor(int maximumDepth, int maximumIncludes,
                              int maximumMacros, int maximumInputBytes,
                              int maximumExpandedBytes) {
        this.maximumDepth = Math.max(1, maximumDepth);
        this.maximumIncludes = Math.max(1, maximumIncludes);
        this.maximumMacros = Math.max(1, maximumMacros);
        this.maximumInputBytes = Math.max(1024, maximumInputBytes);
        this.maximumExpandedBytes = Math.max(this.maximumInputBytes,
            maximumExpandedBytes);
    }

    public PreprocessedShader preprocess(String entryPath,
                                         ShaderSourceRepository repository) {
        return preprocess("", entryPath, repository);
    }

    /**
     * Expands a source while confining every relative and root-style include
     * to {@code includeRoot}.  OptiFine's {@code /lib/...} spelling is rooted
     * at this directory, never at the host filesystem or the pack archive.
     */
    public PreprocessedShader preprocess(String includeRoot, String entryPath,
                                         ShaderSourceRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository");
        String root = includeRoot == null || includeRoot.trim().isEmpty()
            ? "" : normalize("", includeRoot);
        String entry = normalizeWithin(root, "", entryPath, false);
        State state = new State(repository, root);
        expand(entry, state);
        return new PreprocessedShader(state.output.toString(), state.dependencies,
            state.macros.size());
    }

    private void expand(String path, State state) {
        if (state.stack.size() >= maximumDepth) {
            throw new IllegalArgumentException("shader include depth exceeded");
        }
        if (state.includes++ >= maximumIncludes) {
            throw new IllegalArgumentException("shader include count exceeded");
        }
        if (!state.active.add(path)) {
            throw new IllegalArgumentException("shader include cycle: " + path);
        }
        state.stack.push(path);
        try {
            String source = state.repository.load(path);
            if (source == null) throw new IllegalArgumentException("missing shader include: " + path);
            if (source.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("NUL in shader source");
            }
            int remainingInput = maximumInputBytes - state.inputBytes;
            int inputBytes = boundedUtf8Length(source, remainingInput);
            state.inputBytes = checkedAdd(state.inputBytes, inputBytes);
            if (state.inputBytes > maximumInputBytes) {
                throw new IllegalArgumentException("shader input byte limit exceeded");
            }
            state.dependencies.add(path);
            String[] lines = source.split("\\r?\\n", -1);
            String directory = directory(path);
            for (String line : lines) {
                if (line.length() > 65536) throw new IllegalArgumentException("shader line too long");
                Matcher include = INCLUDE.matcher(line);
                if (include.matches()) {
                    String requested = include.group(1);
                    boolean rootStyle = requested.startsWith("/")
                        || requested.startsWith("\\");
                    expand(normalizeWithin(state.includeRoot, directory,
                        requested, rootStyle), state);
                    continue;
                }
                Matcher define = DEFINE.matcher(line);
                if (define.matches()) {
                    String name = define.group(1);
                    String value = define.group(2) == null ? "" : define.group(2);
                    if (value.length() > 65536) throw new IllegalArgumentException("shader macro too large");
                    if (!state.macros.contains(name)) {
                        if (state.macros.size() >= maximumMacros) {
                            throw new IllegalArgumentException("shader macro count exceeded");
                        }
                        state.macros.add(name);
                    }
                }
                append(state, line);
                append(state, "\n");
            }
        } finally {
            state.stack.pop();
            state.active.remove(path);
        }
    }

    private void append(State state, String value) {
        state.expandedBytes = checkedAdd(state.expandedBytes,
            boundedUtf8Length(value, maximumExpandedBytes
                - state.expandedBytes));
        if (state.expandedBytes > maximumExpandedBytes) {
            throw new IllegalArgumentException("expanded shader byte limit exceeded");
        }
        state.output.append(value);
    }

    static String normalize(String directory, String path) {
        if (path == null) throw new IllegalArgumentException("shader path");
        String value = path.replace('\\', '/').trim();
        if (value.isEmpty() || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("absolute shader path");
        }
        String combined = directory == null || directory.isEmpty()
            ? value : directory + "/" + value;
        ArrayDeque<String> segments = new ArrayDeque<String>();
        for (String segment : combined.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) throw new IllegalArgumentException("shader path escapes pack root");
                segments.removeLast();
            } else {
                if (segment.indexOf('\0') >= 0 || segment.indexOf(':') >= 0) {
                    throw new IllegalArgumentException("invalid shader path segment");
                }
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("empty shader path");
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (result.length() > 0) result.append('/');
            result.append(segment);
        }
        return result.toString();
    }

    private static String normalizeWithin(String root, String directory,
                                          String path, boolean rootStyle) {
        if (path == null) throw new IllegalArgumentException("shader path");
        String requested = path.replace('\\', '/');
        while (rootStyle && requested.startsWith("/")) {
            requested = requested.substring(1);
        }
        String base = rootStyle ? root : directory;
        String normalized = normalize(base, requested);
        if (root != null && !root.isEmpty()
            && !normalized.equals(root) && !normalized.startsWith(root + "/")) {
            throw new IllegalArgumentException("shader path escapes include root");
        }
        return normalized;
    }

    private static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static int checkedAdd(int left, int right) {
        if (left < 0 || right < 0 || right > Integer.MAX_VALUE - left) {
            throw new IllegalArgumentException("shader size overflow");
        }
        return left + right;
    }

    private static int boundedUtf8Length(String value, int maximum) {
        if (value == null || maximum < 0) {
            throw new IllegalArgumentException("shader byte limit exceeded");
        }
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
                        "invalid Unicode in shader source");
                }
                index++;
                added = 4;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                    "invalid Unicode in shader source");
            } else added = 3;
            if (added > maximum - bytes) {
                throw new IllegalArgumentException("shader byte limit exceeded");
            }
            bytes += added;
        }
        return bytes;
    }

    private static final class State {
        private final ShaderSourceRepository repository;
        private final String includeRoot;
        private final StringBuilder output = new StringBuilder();
        private final List<String> dependencies = new ArrayList<String>();
        private final Set<String> active = new HashSet<String>();
        private final Set<String> macros = new HashSet<String>();
        private final ArrayDeque<String> stack = new ArrayDeque<String>();
        private int includes;
        private int inputBytes;
        private int expandedBytes;

        private State(ShaderSourceRepository repository, String includeRoot) {
            this.repository = repository;
            this.includeRoot = includeRoot;
        }
    }
}
