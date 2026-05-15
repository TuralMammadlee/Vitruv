package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.List;
import java.util.Objects;

/**
 * Describes a delete-vs-update conflict detected during a branch merge.
 *
 * <p>A deletion conflict occurs when one branch deletes a model element while
 * the other branch modifies that same element (or its children).  The
 * {@link #affectedUpdates} list captures every update on the opposing branch
 * that would be destroyed if the deletion is accepted.
 *
 * <p>The {@link #ancestorAvailable} flag indicates whether a shared Git
 * ancestor exists from which the deleted element can be recovered.  When
 * recovery is possible, the interactive conflict resolver can offer the user
 * a "Recover from ancestor" option.
 *
 * <p><b>Severity is derived from a weighted impact score</b>, not the raw
 * lost-update count. Each lost delta contributes a weight based on its
 * {@link ChangeOrigin}: ORIGINAL (human) edits weigh
 * {@value #ORIGINAL_WEIGHT}, CONSEQUENTIAL (engine-generated) edits weigh
 * {@value #CONSEQUENTIAL_WEIGHT}, and UNKNOWN edits weigh
 * {@value #UNKNOWN_WEIGHT}. The summed score is then mapped to a
 * {@link ConflictSeverity} through the configured {@link SeverityThresholds}.
 * This means that destroying a single human change can route a conflict to
 * the same severity bucket as destroying multiple engine-generated changes,
 * which is exactly the routing decision the role-based clearance system
 * (via {@link MergePolicy} and
 * {@link tools.vitruv.framework.vsum.branch.storage.RoleManager}) relies on.
 */
public class DeletionConflict {

    /** Weight assigned to a lost ORIGINAL (human) edit when computing impact. */
    static final int ORIGINAL_WEIGHT = 2;
    /** Weight assigned to a lost CONSEQUENTIAL (engine-generated) edit. */
    static final int CONSEQUENTIAL_WEIGHT = 1;
    /** Weight assigned to a lost edit whose origin is UNKNOWN — treated like CONSEQUENTIAL to avoid inflating noise. */
    static final int UNKNOWN_WEIGHT = 1;

    private final String deletedElementUuid;
    private final String deletedElementEClass;
    private final String deletingBranch;
    private final String updatingBranch;
    private final List<SemanticChangeEntry> affectedUpdates;
    private final boolean ancestorAvailable;
    private final ChangeOrigin deletionOrigin;

    /**
     * Creates a new deletion conflict descriptor.
     *
     * @param deletedElementUuid  UUID of the element that was deleted.
     * @param deletedElementEClass  EClass name of the deleted element (for display).
     * @param deletingBranch      name of the branch that deleted the element.
     * @param updatingBranch      name of the branch that updated the element.
     * @param affectedUpdates     updates on the opposite branch that touch the deleted
     *                            element or its children (will be lost on acceptance).
     * @param ancestorAvailable   whether a shared ancestor exists for recovery.
     * @param deletionOrigin      whether the deletion was human-made or engine-generated.
     */
    public DeletionConflict(String deletedElementUuid, String deletedElementEClass,
                            String deletingBranch, String updatingBranch,
                            List<SemanticChangeEntry> affectedUpdates,
                            boolean ancestorAvailable, ChangeOrigin deletionOrigin) {
        this.deletedElementUuid = Objects.requireNonNull(deletedElementUuid);
        this.deletedElementEClass = deletedElementEClass;
        this.deletingBranch = Objects.requireNonNull(deletingBranch);
        this.updatingBranch = Objects.requireNonNull(updatingBranch);
        this.affectedUpdates = Objects.requireNonNull(affectedUpdates);
        this.ancestorAvailable = ancestorAvailable;
        this.deletionOrigin = deletionOrigin != null ? deletionOrigin : ChangeOrigin.UNKNOWN;
    }

    public String getDeletedElementUuid() { return deletedElementUuid; }
    public String getDeletedElementEClass() { return deletedElementEClass; }
    public String getDeletingBranch() { return deletingBranch; }
    public String getUpdatingBranch() { return updatingBranch; }
    public List<SemanticChangeEntry> getAffectedUpdates() { return affectedUpdates; }
    public boolean isAncestorAvailable() { return ancestorAvailable; }
    public ChangeOrigin getDeletionOrigin() { return deletionOrigin; }

    /**
     * Returns the number of updates that would be destroyed if this deletion
     * is accepted. This is the raw count and is used for role-based update-limit
     * checks (see {@link RoleDefinition#canApproveUpdatesLost(int)}). For
     * severity routing, see {@link #getWeightedImpact()}.
     */
    public int getLostUpdateCount() {
        return affectedUpdates.size();
    }

    /**
     * Returns the weighted impact score of accepting this deletion.
     *
     * <p>Each affected update contributes a weight based on its
     * {@link ChangeOrigin}:
     * <ul>
     *   <li>ORIGINAL (human edit) → {@value #ORIGINAL_WEIGHT}</li>
     *   <li>CONSEQUENTIAL (engine-generated) → {@value #CONSEQUENTIAL_WEIGHT}</li>
     *   <li>UNKNOWN → {@value #UNKNOWN_WEIGHT}</li>
     * </ul>
     *
     * <p>This score is the input to {@link #getSeverity()} and therefore the
     * value that ultimately routes the conflict to the
     * {@link tools.vitruv.framework.vsum.branch.storage.RoleManager} for
     * clearance.
     */
    public int getWeightedImpact() {
        int total = 0;
        for (SemanticChangeEntry update : affectedUpdates) {
            total += weightFor(update.getOrigin());
        }
        return total;
    }

    private static int weightFor(ChangeOrigin origin) {
        if (origin == ChangeOrigin.ORIGINAL) return ORIGINAL_WEIGHT;
        if (origin == ChangeOrigin.CONSEQUENTIAL) return CONSEQUENTIAL_WEIGHT;
        return UNKNOWN_WEIGHT;
    }

    /**
     * Returns {@code true} if the deletion was consequential (engine-generated)
     * but at least one of the conflicting updates was original (human-made).
     * In this case, the Vitruvius rule "original &gt; consequential" strongly
     * recommends recovery.
     */
    public boolean isConsequentialDeletionVsOriginalUpdates() {
        if (deletionOrigin != ChangeOrigin.CONSEQUENTIAL) {
            return false;
        }
        return affectedUpdates.stream()
                .anyMatch(u -> u.getOrigin() == ChangeOrigin.ORIGINAL);
    }

    /**
     * Returns {@code true} if this conflict is high-impact, defined as having
     * more lost updates than the given threshold.
     */
    public boolean isHighImpact(int threshold) {
        return getLostUpdateCount() >= threshold;
    }

    /**
     * Computes the {@link ConflictSeverity} of this conflict from
     * {@link #getWeightedImpact()} using the default {@link SeverityThresholds}.
     * Lost ORIGINAL edits contribute more to the score than lost CONSEQUENTIAL
     * edits, so a small number of human changes can produce the same severity
     * as a larger number of engine-generated changes.
     */
    public ConflictSeverity getSeverity() {
        return ConflictSeverity.fromLostUpdateCount(getWeightedImpact());
    }

    /**
     * Computes the {@link ConflictSeverity} of this conflict from
     * {@link #getWeightedImpact()} using the supplied {@link SeverityThresholds}.
     */
    public ConflictSeverity getSeverity(SeverityThresholds thresholds) {
        return ConflictSeverity.fromLostUpdateCount(getWeightedImpact(), thresholds);
    }

    @Override
    public String toString() {
        return "DeletionConflict{" +
                "element=" + deletedElementEClass + " (uuid=" + deletedElementUuid + ")" +
                ", deletingBranch='" + deletingBranch + '\'' +
                ", updatingBranch='" + updatingBranch + '\'' +
                ", lostUpdates=" + affectedUpdates.size() +
                ", weightedImpact=" + getWeightedImpact() +
                ", severity=" + getSeverity() +
                ", ancestorAvailable=" + ancestorAvailable +
                ", deletionOrigin=" + deletionOrigin +
                '}';
    }
}
