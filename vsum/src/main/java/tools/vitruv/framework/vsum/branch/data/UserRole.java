package tools.vitruv.framework.vsum.branch.data;

/**
 * Represents the experience level of a developer performing a merge.
 *
 * <p>User roles govern what actions are permitted during conflict resolution:
 * <ul>
 *   <li>{@link #JUNIOR} — cannot approve high-impact deletions, must escalate</li>
 *   <li>{@link #SENIOR} — can approve all deletions with confirmation</li>
 *   <li>{@link #ARCHITECT} — full access, can auto-approve or override any policy</li>
 * </ul>
 *
 * @see MergePolicy
 */
public enum UserRole {

    /**
     * A junior developer who needs strict guardrails.  Cannot approve
     * high-impact deletions (those exceeding the policy's threshold).
     */
    JUNIOR("Junior developer - restricted deletion approval"),

    /**
     * A senior developer who can approve all deletions after confirmation.
     */
    SENIOR("Senior developer - can approve deletions with confirmation"),

    /**
     * A system architect with full control over merge policy decisions.
     * May auto-approve or override any conflict resolution.
     */
    ARCHITECT("Architect - full merge authority");

    private final String description;

    UserRole(String description) {
        this.description = description;
    }

    /**
     * Returns a human-readable description of this role's permissions.
     */
    public String getDescription() {
        return description;
    }
}
