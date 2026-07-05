package tools.vitruv.framework.vsum.branch.storage;

/**
 * Configuration for owner no-response timeout before re-routing to a senior role.
 */
public final class OwnerEscalationConfig {

    /** Default hours to wait for an owner decision before senior escalation. */
    public static final long DEFAULT_NO_RESPONSE_TIMEOUT_HOURS = 72;

    private OwnerEscalationConfig() {
    }

    /**
     * Returns the configured no-response timeout in hours.
     *
     * <p>Override via environment variable {@code VITRUV_OWNER_RESPONSE_TIMEOUT_HOURS}.
     * A value of {@code 0} means no-response immediately triggers senior escalation
     * in headless environments (useful in tests).
     */
    public static long getNoResponseTimeoutHours() {
        String sysProp = System.getProperty("vitruv.owner.response.timeout.hours");
        if (sysProp != null && !sysProp.isBlank()) {
            try {
                return Long.parseLong(sysProp.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        String env = System.getenv("VITRUV_OWNER_RESPONSE_TIMEOUT_HOURS");
        if (env != null && !env.isBlank()) {
            try {
                return Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_NO_RESPONSE_TIMEOUT_HOURS;
    }
}
