package tools.vitruv.framework.vsum.branch.data;

import java.util.Objects;

/**
 * Immutable severity summary attached to a {@link ConflictReview} (part of the
 * activity-diagram box {@code owner_review}). It captures the numeric and
 * classified risk of a deletion conflict together with the role-clearance gap
 * that triggered the escalation, so the owner can see why the conflict was
 * routed to them.
 */
public final class SeverityReport {

    private final int weightedImpact;
    private final int lostUpdateCount;
    private final ConflictSeverity severity;
    private final String severityDescription;
    private final SeverityThresholds thresholds;
    private final String roleName;
    private final boolean roleCanClear;

    private SeverityReport(int weightedImpact, int lostUpdateCount, ConflictSeverity severity,
                           String severityDescription, SeverityThresholds thresholds,
                           String roleName, boolean roleCanClear) {
        this.weightedImpact = weightedImpact;
        this.lostUpdateCount = lostUpdateCount;
        this.severity = severity;
        this.severityDescription = severityDescription;
        this.thresholds = thresholds;
        this.roleName = roleName;
        this.roleCanClear = roleCanClear;
    }

    /**
     * Builds a severity report for a deletion conflict under the given
     * thresholds and the role whose clearance was evaluated.
     *
     * @param conflict   the escalated deletion conflict.
     * @param thresholds the severity thresholds used for classification.
     * @param role       the role whose (insufficient) clearance is being reported.
     */
    public static SeverityReport forConflict(DeletionConflict conflict, SeverityThresholds thresholds,
                                             RoleDefinition role) {
        Objects.requireNonNull(conflict, "conflict must not be null");
        Objects.requireNonNull(role, "role must not be null");
        SeverityThresholds effective = thresholds != null ? thresholds : SeverityThresholds.defaults();
        ConflictSeverity severity = conflict.getSeverity(effective);
        boolean roleCanClear = role.canApproveUpdatesLost(conflict.getLostUpdateCount())
                && role.canResolveSeverity(severity);
        return new SeverityReport(
                conflict.getWeightedImpact(),
                conflict.getLostUpdateCount(),
                severity,
                severity.getDescription(),
                effective,
                role.getName(),
                roleCanClear);
    }

    public int getWeightedImpact() { return weightedImpact; }
    public int getLostUpdateCount() { return lostUpdateCount; }
    public ConflictSeverity getSeverity() { return severity; }
    public String getSeverityDescription() { return severityDescription; }
    public SeverityThresholds getThresholds() { return thresholds; }

    /** The role whose clearance was evaluated for this conflict. */
    public String getRoleName() { return roleName; }

    /**
     * Whether the evaluated role could clear this conflict on its own. When
     * {@code false}, the conflict was escalated to the owner.
     */
    public boolean isRoleCanClear() { return roleCanClear; }

    @Override
    public String toString() {
        return "SeverityReport{" +
                "severity=" + severity +
                ", weightedImpact=" + weightedImpact +
                ", lostUpdates=" + lostUpdateCount +
                ", role=" + roleName +
                ", roleCanClear=" + roleCanClear +
                '}';
    }
}
