package dev.rlcraft.ice.optimizer.render.backend;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Result of executable GL self-tests; extension strings alone never pass. */
public final class CapabilityReport {
    private final EnumSet<ModernCapability> passed;
    private final Map<ModernCapability, String> failures;
    private final Map<ModernCapability, FailureDetail> failureDetails;

    private CapabilityReport(EnumSet<ModernCapability> passed,
                             Map<ModernCapability, String> failures,
                             Map<ModernCapability, FailureDetail> failureDetails) {
        this.passed = passed.isEmpty() ? EnumSet.noneOf(ModernCapability.class)
            : EnumSet.copyOf(passed);
        this.failures = Collections.unmodifiableMap(
            new EnumMap<ModernCapability, String>(failures));
        this.failureDetails = Collections.unmodifiableMap(
            new EnumMap<ModernCapability, FailureDetail>(failureDetails));
    }

    public boolean passed(ModernCapability capability) {
        return capability != null && passed.contains(capability);
    }

    /** True when the capability has an explicit pass or failure outcome. */
    public boolean reported(ModernCapability capability) {
        return capability != null && (passed.contains(capability)
            || failureDetails.containsKey(capability));
    }

    public boolean satisfies(EnumSet<ModernCapability> required) {
        return required == null || passed.containsAll(required);
    }

    public EnumSet<ModernCapability> getPassed() {
        return passed.isEmpty() ? EnumSet.noneOf(ModernCapability.class)
            : EnumSet.copyOf(passed);
    }

    public Map<ModernCapability, String> getFailures() { return failures; }

    /** Structured diagnostics used by optimizer-renderer.txt. */
    public Map<ModernCapability, FailureDetail> getFailureDetails() {
        return failureDetails;
    }

    public FailureDetail getFailureDetail(ModernCapability capability) {
        return capability == null ? null : failureDetails.get(capability);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final EnumSet<ModernCapability> passed =
            EnumSet.noneOf(ModernCapability.class);
        private final EnumMap<ModernCapability, String> failures =
            new EnumMap<ModernCapability, String>(ModernCapability.class);
        private final EnumMap<ModernCapability, FailureDetail> failureDetails =
            new EnumMap<ModernCapability, FailureDetail>(ModernCapability.class);

        public Builder pass(ModernCapability capability) {
            if (capability == null) throw new IllegalArgumentException("capability");
            failures.remove(capability);
            failureDetails.remove(capability);
            passed.add(capability);
            return this;
        }

        public Builder fail(ModernCapability capability, String detail) {
            return fail(capability, FailureDetail.message(detail));
        }

        public Builder fail(ModernCapability capability, FailureDetail detail) {
            if (capability == null) throw new IllegalArgumentException("capability");
            passed.remove(capability);
            FailureDetail normalized = detail == null
                ? FailureDetail.message("self-test failed") : detail;
            failures.put(capability, normalized.summary());
            failureDetails.put(capability, normalized);
            return this;
        }

        /**
         * Completes a report after a global prerequisite or orchestration
         * failure without overwriting probes which already produced evidence.
         */
        public Builder failUnreported(FailureDetail detail) {
            ModernCapability[] capabilities = ModernCapability.values();
            for (int index = 0; index < capabilities.length; index++) {
                ModernCapability capability = capabilities[index];
                if (!passed.contains(capability)
                    && !failureDetails.containsKey(capability)) {
                    fail(capability, detail);
                }
            }
            return this;
        }

        public CapabilityReport build() {
            return new CapabilityReport(passed, failures, failureDetails);
        }
    }

    /**
     * Immutable and bounded failure evidence.  GL errors are intentionally
     * absent on successful tests because querying them consumes shared context
     * state; executable probes populate that field only after a failure.
     */
    public static final class FailureDetail {
        private static final int MAX_FIELD_LENGTH = 4096;
        private final String stage;
        private final String exceptionType;
        private final String message;
        private final String glState;
        private final String glErrors;

        public FailureDetail(String stage, String exceptionType, String message,
                             String glState, String glErrors) {
            this.stage = bounded(stage);
            this.exceptionType = bounded(exceptionType);
            this.message = bounded(message == null || message.isEmpty()
                ? "self-test failed" : message);
            this.glState = bounded(glState);
            this.glErrors = bounded(glErrors);
        }

        public static FailureDetail message(String message) {
            return new FailureDetail("self-test", "", message, "", "");
        }

        public String getStage() { return stage; }
        public String getExceptionType() { return exceptionType; }
        public String getMessage() { return message; }
        public String getGlState() { return glState; }
        public String getGlErrors() { return glErrors; }

        public String summary() {
            return exceptionType.isEmpty() ? message
                : exceptionType + (message.isEmpty() ? "" : ": " + message);
        }

        private static String bounded(String value) {
            if (value == null || value.isEmpty()) return "";
            return value.length() <= MAX_FIELD_LENGTH ? value
                : value.substring(0, MAX_FIELD_LENGTH);
        }
    }
}
