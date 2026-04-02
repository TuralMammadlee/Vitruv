package tools.vitruv.framework.vsum.branch.data;

import java.util.Objects;

/**
 * Configurable merge policy that governs how deletion conflicts are resolved.
 *
 * <p>Combines three aspects from the supervisor's requirements:
 * <ol>
 *   <li><b>Default deletion policy</b> — which {@link DeletionPolicy} to use
 *       when no interactive choice is made (e.g. headless mode).</li>
 *   <li><b>User role</b> — determines whether the current user is allowed to
 *       approve high-impact deletions.</li>
 *   <li><b>High-impact threshold</b> — the number of lost updates that
 *       qualifies a deletion as "high impact", blocking junior developers
 *       from approving it.</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>
 * MergePolicy policy = MergePolicy.forRole(UserRole.JUNIOR);
 * // or with explicit configuration:
 * MergePolicy policy = new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, UserRole.SENIOR, 5);
 * </pre>
 *
 * @see DeletionConflict
 * @see UserRole
 * @see DeletionPolicy
 */
public class MergePolicy {

    /**
     * Default threshold: deletions that destroy 3 or more updates are
     * considered high-impact and require elevated permissions.
     */
    public static final int DEFAULT_HIGH_IMPACT_THRESHOLD = 3;

    private final DeletionPolicy defaultDeletionPolicy;
    private final UserRole userRole;
    private final int highImpactThreshold;

    /**
     * Creates a merge policy with explicit configuration.
     *
     * @param defaultDeletionPolicy the fallback policy when no interactive
     *                              choice is made or in headless mode.
     * @param userRole              the current user's role.
     * @param highImpactThreshold   number of lost updates that qualifies as
     *                              high impact (must be &gt; 0).
     */
    public MergePolicy(DeletionPolicy defaultDeletionPolicy, UserRole userRole,
                        int highImpactThreshold) {
        this.defaultDeletionPolicy = Objects.requireNonNull(defaultDeletionPolicy);
        this.userRole = Objects.requireNonNull(userRole);
        if (highImpactThreshold <= 0) {
            throw new IllegalArgumentException("highImpactThreshold must be > 0, got " + highImpactThreshold);
        }
        this.highImpactThreshold = highImpactThreshold;
    }

    /**
     * Convenience factory: creates a policy for the given role with the
     * default deletion policy ({@link DeletionPolicy#RECOVER_FROM_ANCESTOR})
     * and the default high-impact threshold ({@value #DEFAULT_HIGH_IMPACT_THRESHOLD}).
     */
    public static MergePolicy forRole(UserRole role) {
        return new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, role,
                DEFAULT_HIGH_IMPACT_THRESHOLD);
    }

    /**
     * Returns whether the current user is allowed to approve the given
     * deletion conflict.
     *
     * <ul>
     *   <li>{@link UserRole#JUNIOR}: blocked if the conflict is high-impact</li>
     *   <li>{@link UserRole#SENIOR}: always allowed (with confirmation)</li>
     *   <li>{@link UserRole#ARCHITECT}: always allowed</li>
     * </ul>
     */
    public boolean canApproveDeletion(DeletionConflict conflict) {
        Objects.requireNonNull(conflict);
        return switch (userRole) {
            case JUNIOR -> !conflict.isHighImpact(highImpactThreshold);
            case SENIOR, ARCHITECT -> true;
        };
    }

    /**
     * Returns whether the given conflict requires escalation to a more
     * senior team member.  Only applicable for {@link UserRole#JUNIOR}.
     */
    public boolean requiresEscalation(DeletionConflict conflict) {
        Objects.requireNonNull(conflict);
        return userRole == UserRole.JUNIOR && conflict.isHighImpact(highImpactThreshold);
    }

    public DeletionPolicy getDefaultDeletionPolicy() { return defaultDeletionPolicy; }
    public UserRole getUserRole() { return userRole; }
    public int getHighImpactThreshold() { return highImpactThreshold; }

    @Override
    public String toString() {
        return "MergePolicy{" +
                "defaultPolicy=" + defaultDeletionPolicy +
                ", userRole=" + userRole +
                ", highImpactThreshold=" + highImpactThreshold +
                '}';
    }
}
