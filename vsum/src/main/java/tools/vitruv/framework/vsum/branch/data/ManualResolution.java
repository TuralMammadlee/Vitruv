package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.Objects;

/**
 * Records the outcome of an interactive (human) resolution of a single
 * {@link UpdateConflict} that the automatic tiers could not settle.
 *
 * <p>This is the update-conflict counterpart of
 * {@link tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver.Resolution}.
 * It is produced by a
 * {@link tools.vitruv.framework.vsum.branch.ConflictResolutionStrategy} and is
 * deliberately a pure value object so that the strategy (UI), the resolver, and
 * the audit log can stay decoupled.
 *
 * <p>A resolution is either a concrete choice of one side
 * ({@link Decision#ACCEPT_SOURCE} / {@link Decision#ACCEPT_TARGET}) carrying the
 * winning {@link SemanticChangeEntry}, or a {@link Decision#DEFERRED} marker for
 * conflicts the human chose to (or had to) leave open.
 */
public final class ManualResolution {

    /** Which side the human selected, or whether the decision was deferred. */
    public enum Decision { ACCEPT_SOURCE, ACCEPT_TARGET, DEFERRED }

    private final UpdateConflict conflict;
    private final Decision decision;
    private final SemanticChangeEntry chosenEntry;
    private final String rationale;

    private ManualResolution(UpdateConflict conflict, Decision decision,
                             SemanticChangeEntry chosenEntry, String rationale) {
        this.conflict = Objects.requireNonNull(conflict, "conflict must not be null");
        this.decision = Objects.requireNonNull(decision, "decision must not be null");
        this.chosenEntry = chosenEntry;
        this.rationale = rationale;
    }

    /**
     * The human accepted the source (incoming) branch's change.
     *
     * @param conflict  the conflict being resolved.
     * @param rationale optional free-text justification; may be {@code null}.
     */
    public static ManualResolution acceptSource(UpdateConflict conflict, String rationale) {
        return new ManualResolution(conflict, Decision.ACCEPT_SOURCE,
                conflict.getSourceEntry(), rationale);
    }

    /**
     * The human accepted the target (current) branch's change.
     *
     * @param conflict  the conflict being resolved.
     * @param rationale optional free-text justification; may be {@code null}.
     */
    public static ManualResolution acceptTarget(UpdateConflict conflict, String rationale) {
        return new ManualResolution(conflict, Decision.ACCEPT_TARGET,
                conflict.getTargetEntry(), rationale);
    }

    /**
     * No decision was made; the conflict remains open for a later pass or a
     * different tool.
     *
     * @param conflict the conflict left unresolved.
     * @param reason   why it was deferred (e.g. headless mode, user skipped).
     */
    public static ManualResolution deferred(UpdateConflict conflict, String reason) {
        return new ManualResolution(conflict, Decision.DEFERRED, null, reason);
    }

    public UpdateConflict getConflict() { return conflict; }
    public Decision getDecision() { return decision; }

    /**
     * Returns the winning change entry, or {@code null} when the decision was
     * {@link Decision#DEFERRED}.
     */
    public SemanticChangeEntry getChosenEntry() { return chosenEntry; }

    /**
     * Returns the human-provided rationale (for accepted decisions) or the
     * deferral reason. May be {@code null} when an accepted decision carried no
     * annotation.
     */
    public String getRationale() { return rationale; }

    /** Returns {@code true} when a concrete side was chosen. */
    public boolean isResolved() { return decision != Decision.DEFERRED; }

    /**
     * Returns the branch name of the chosen side, or {@code null} when deferred.
     */
    public String getChosenBranch() {
        return switch (decision) {
            case ACCEPT_SOURCE -> conflict.getSourceBranch();
            case ACCEPT_TARGET -> conflict.getTargetBranch();
            case DEFERRED -> null;
        };
    }

    @Override
    public String toString() {
        return "ManualResolution{" + conflict.getEClass() + "." + conflict.getFeatureName()
                + ", decision=" + decision
                + (rationale != null ? ", rationale='" + rationale + "'" : "")
                + '}';
    }
}
