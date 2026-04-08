package tools.vitruv.framework.vsum.branch.data;

/**
 * Classifies the severity of a merge conflict based on its potential impact
 * on the model's integrity.
 *
 * <p>Severity levels are ordered: {@link #LOW} &lt; {@link #MEDIUM} &lt;
 * {@link #HIGH} &lt; {@link #CRITICAL}. Each {@link RoleDefinition} specifies
 * a maximum severity that the role is allowed to resolve. If a conflict's
 * severity exceeds the role's maximum, the system blocks the resolution and
 * requires escalation to a more senior role.
 *
 * <p>Example: if a {@code DEVELOPER} role has {@code maxSeverity = MEDIUM},
 * they can resolve LOW and MEDIUM conflicts but are blocked from resolving
 * HIGH or CRITICAL conflicts.
 *
 * @see RoleDefinition
 * @see MergePolicy
 */
public enum ConflictSeverity {

    /**
     * Non-overlapping additions or independent attribute changes on different
     * elements. Minimal risk of data loss.
     */
    LOW(1, "Non-overlapping changes with minimal risk"),

    /**
     * Concurrent updates to the same attribute or reference on the same
     * element. One branch's value will overwrite the other.
     */
    MEDIUM(2, "Concurrent updates on the same element"),

    /**
     * Delete-vs-update conflict where one branch deletes an element while
     * the other modifies it. Accepting the deletion destroys the updates.
     */
    HIGH(3, "Delete-vs-update conflict with potential data loss"),

    /**
     * Cascading tree deletion affecting many elements. Accepting the
     * deletion destroys a significant portion of the model.
     */
    CRITICAL(4, "Cascading deletion affecting many elements");

    private final int level;
    private final String description;

    ConflictSeverity(int level, String description) {
        this.level = level;
        this.description = description;
    }

    /**
     * Returns the numeric level of this severity. Higher values indicate
     * more severe conflicts.
     */
    public int getLevel() {
        return level;
    }

    /**
     * Returns a human-readable description of this severity level.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns {@code true} if this severity is at or below the given
     * maximum severity. Used by {@link MergePolicy} to check whether the
     * current user's role permits resolving a conflict of this severity.
     *
     * @param maxAllowed the maximum severity the user is permitted to resolve.
     * @return true if this severity can be resolved by someone with the given max.
     */
    public boolean isWithin(ConflictSeverity maxAllowed) {
        if (maxAllowed == null) {
            return false;
        }
        return this.level <= maxAllowed.level;
    }

    /**
     * Computes the appropriate severity for a deletion conflict based on the
     * number of updates that would be lost, using the default thresholds.
     *
     * <ul>
     *   <li>0 lost updates → {@link #LOW}</li>
     *   <li>1–2 lost updates → {@link #MEDIUM}</li>
     *   <li>3–9 lost updates → {@link #HIGH}</li>
     *   <li>10+ lost updates → {@link #CRITICAL}</li>
     * </ul>
     *
     * @param lostUpdateCount the number of updates that would be destroyed.
     * @return the computed severity.
     */
    public static ConflictSeverity fromLostUpdateCount(int lostUpdateCount) {
        return fromLostUpdateCount(lostUpdateCount, SeverityThresholds.defaults());
    }

    /**
     * Computes the appropriate severity using configurable thresholds.
     *
     * @param lostUpdateCount the number of updates that would be destroyed.
     * @param thresholds      the severity boundaries to apply.
     * @return the computed severity.
     * @see SeverityThresholds
     */
    public static ConflictSeverity fromLostUpdateCount(int lostUpdateCount, SeverityThresholds thresholds) {
        if (thresholds == null) {
            thresholds = SeverityThresholds.defaults();
        }
        return thresholds.computeSeverity(lostUpdateCount);
    }
}
