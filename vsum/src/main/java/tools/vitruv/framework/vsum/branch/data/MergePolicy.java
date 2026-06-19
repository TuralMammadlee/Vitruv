package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.RoleManager;

import java.util.Objects;
import java.util.Set;

/**
 * Configurable merge policy that governs how deletion conflicts are resolved
 * based on the current user's {@link RoleDefinition}.
 *
 * <p>Combines three aspects:
 * <ol>
 *   <li><b>Default deletion policy</b> — which {@link DeletionPolicy} to use
 *       when no interactive choice is made (e.g. headless mode).</li>
 *   <li><b>Role-based permissions</b> — the user's role determines how many
 *       updates they may sacrifice and what severity they can resolve.</li>
 *   <li><b>Conflict severity</b> — high-severity conflicts (affecting many
 *       elements) require a role with sufficient authority.</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>
 * // From a RoleManager (preferred):
 * MergePolicy policy = MergePolicy.forCurrentUser(roleManager);
 *
 * // Or with an explicit role:
 * RoleDefinition dev = RoleDefinition.developer();
 * MergePolicy policy = MergePolicy.forRole(dev);
 *
 * // Or fully manual:
 * MergePolicy policy = new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, myRole);
 * </pre>
 *
 * @see DeletionConflict
 * @see RoleDefinition
 * @see ConflictSeverity
 * @see DeletionPolicy
 */
public class MergePolicy {

    private final DeletionPolicy defaultDeletionPolicy;
    private final RoleDefinition role;
    private final String currentUserId;
    private final SeverityThresholds severityThresholds;

    /**
     * Creates a merge policy with explicit configuration.
     *
     * @param defaultDeletionPolicy the fallback policy when no interactive
     *                              choice is made or in headless mode.
     * @param role                  the role definition governing permissions.
     * @param severityThresholds    custom thresholds for severity limits.
     */
    public MergePolicy(DeletionPolicy defaultDeletionPolicy, RoleDefinition role, SeverityThresholds severityThresholds) {
        this(defaultDeletionPolicy, role, null, severityThresholds);
    }

    /**
     * Creates a merge policy with explicit current user identity.
     *
     * @param defaultDeletionPolicy the fallback policy when no interactive
     *                              choice is made or in headless mode.
     * @param role                  the role definition governing permissions.
     * @param currentUserId         the identity (normalized email) of the user
     *                              approving the merge, used for owner-priority
     *                              checks. May be {@code null}.
     * @param severityThresholds    custom thresholds for severity limits.
     */
    public MergePolicy(DeletionPolicy defaultDeletionPolicy, RoleDefinition role,
                       String currentUserId, SeverityThresholds severityThresholds) {
        this.defaultDeletionPolicy = Objects.requireNonNull(defaultDeletionPolicy,
                "defaultDeletionPolicy must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.currentUserId = normalizeUserId(currentUserId);
        this.severityThresholds = severityThresholds != null ? severityThresholds : SeverityThresholds.defaults();
    }

    /**
     * Creates a merge policy with default severity thresholds.
     */
    public MergePolicy(DeletionPolicy defaultDeletionPolicy, RoleDefinition role) {
        this(defaultDeletionPolicy, role, null, SeverityThresholds.defaults());
    }

    /**
     * Convenience factory: creates a policy for the given role with the
     * default deletion policy ({@link DeletionPolicy#RECOVER_FROM_ANCESTOR}).
     */
    public static MergePolicy forRole(RoleDefinition role) {
        return new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, role);
    }

    /**
     * Convenience factory: creates a policy for the current Git user
     * by resolving their role and identity from the {@link RoleManager}.
     */
    public static MergePolicy forCurrentUser(RoleManager roleManager) {
        return new MergePolicy(
                DeletionPolicy.RECOVER_FROM_ANCESTOR,
                roleManager.getCurrentUserRole(),
                roleManager.getCurrentUserId(),
                SeverityThresholds.defaults());
    }

    /**
     * Returns whether the current user's role is allowed to approve the
     * given deletion conflict.
     *
     * <p>Approval requires both:
     * <ul>
     *   <li>The conflict's lost update count is within the role's limit
     *       (or the role has unlimited approval)</li>
     *   <li>The conflict's severity is within the role's maximum allowed
     *       severity</li>
     * </ul>
     */
    public boolean canApproveDeletion(DeletionConflict conflict) {
        Objects.requireNonNull(conflict, "conflict must not be null");
        if (isCurrentUserDetectedOwner(conflict.getDetectedOwners())) {
            return true;
        }
        boolean updatesOk = role.canApproveUpdatesLost(conflict.getLostUpdateCount());
        boolean severityOk = role.canResolveSeverity(conflict.getSeverity(severityThresholds));
        return updatesOk && severityOk;
    }

    /**
     * Returns whether the given conflict requires escalation to a more
     * senior role. True when the current role cannot approve the conflict.
     */
    public boolean requiresEscalation(DeletionConflict conflict) {
        Objects.requireNonNull(conflict, "conflict must not be null");
        return !canApproveDeletion(conflict);
    }

    public DeletionPolicy getDefaultDeletionPolicy() { return defaultDeletionPolicy; }
    public RoleDefinition getRole() { return role; }
    public String getCurrentUserId() { return currentUserId; }
    public SeverityThresholds getSeverityThresholds() { return severityThresholds; }

    /**
     * Returns the role name for display in the CLI resolver.
     */
    public String getRoleName() { return role.getName(); }

    /**
     * Returns whether the current user is one of the detected owners of a conflict.
     * Returns {@code false} when no current user identity is known or no owners
     * were detected.
     */
    public boolean isCurrentUserDetectedOwner(Set<String> detectedOwners) {
        if (currentUserId == null || detectedOwners == null || detectedOwners.isEmpty()) {
            return false;
        }
        return detectedOwners.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeUserId)
                .anyMatch(currentUserId::equals);
    }

    private String normalizeUserId(String userId) {
        if (userId == null) {
            return null;
        }
        String normalized = userId.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    @Override
    public String toString() {
        return "MergePolicy{" +
                "defaultPolicy=" + defaultDeletionPolicy +
                ", role=" + role.getName() +
                ", level=" + role.getPermissionLevel() +
                '}';
    }
}
