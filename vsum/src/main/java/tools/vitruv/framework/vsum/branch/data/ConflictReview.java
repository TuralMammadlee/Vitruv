package tools.vitruv.framework.vsum.branch.data;

import java.util.List;
import java.util.Set;

/**
 * Immutable review package presented to an owner when a deletion conflict is
 * escalated (activity-diagram box {@code owner_review}). It assembles the three
 * things the owner needs to decide:
 *
 * <ul>
 *   <li><b>Conflict history</b> ({@link #getHistory()}) — the changelog entries
 *       from both merge branches that touch the conflicting element(s).</li>
 *   <li><b>State previews</b> ({@link #getStatePreviews()}) — change-level
 *       before/after of the updates that would be destroyed by the deletion.</li>
 *   <li><b>Severity report</b> ({@link #getSeverityReport()}) — the risk score
 *       and role-clearance gap that triggered the escalation.</li>
 * </ul>
 *
 * <p>Serialized to JSON by
 * {@link tools.vitruv.framework.vsum.branch.storage.ConflictReviewService}
 * under {@code .vitruvius/reviews/}.
 */
public final class ConflictReview {

    private final String deletedElementUuid;
    private final String deletedElementEClass;
    private final String deletingBranch;
    private final String updatingBranch;
    private final Set<String> detectedOwners;
    private final String deletionOrigin;
    private final boolean ancestorRecoverable;
    private final SeverityReport severityReport;
    private final List<ReviewChange> history;
    private final List<ReviewChange> statePreviews;

    public ConflictReview(String deletedElementUuid, String deletedElementEClass,
                          String deletingBranch, String updatingBranch, Set<String> detectedOwners,
                          String deletionOrigin, boolean ancestorRecoverable,
                          SeverityReport severityReport, List<ReviewChange> history,
                          List<ReviewChange> statePreviews) {
        this.deletedElementUuid = deletedElementUuid;
        this.deletedElementEClass = deletedElementEClass;
        this.deletingBranch = deletingBranch;
        this.updatingBranch = updatingBranch;
        this.detectedOwners = detectedOwners;
        this.deletionOrigin = deletionOrigin;
        this.ancestorRecoverable = ancestorRecoverable;
        this.severityReport = severityReport;
        this.history = history;
        this.statePreviews = statePreviews;
    }

    public String getDeletedElementUuid() { return deletedElementUuid; }
    public String getDeletedElementEClass() { return deletedElementEClass; }
    public String getDeletingBranch() { return deletingBranch; }
    public String getUpdatingBranch() { return updatingBranch; }
    public Set<String> getDetectedOwners() { return detectedOwners; }
    public String getDeletionOrigin() { return deletionOrigin; }

    /** Whether the deleted element can be recovered from a shared ancestor. */
    public boolean isAncestorRecoverable() { return ancestorRecoverable; }
    public SeverityReport getSeverityReport() { return severityReport; }

    /** Changelog entries from both branches that touch the conflicting element(s). */
    public List<ReviewChange> getHistory() { return history; }

    /** Change-level previews of the updates that would be destroyed by the deletion. */
    public List<ReviewChange> getStatePreviews() { return statePreviews; }

    /**
     * One change record shown in a review, used both for conflict history and
     * for change-level state previews.
     */
    public static final class ReviewChange {
        private final String branch;
        private final String elementUuid;
        private final String eClass;
        private final String feature;
        private final String changeType;
        private final String origin;
        private final String from;
        private final String to;

        public ReviewChange(String branch, String elementUuid, String eClass, String feature,
                            String changeType, String origin, String from, String to) {
            this.branch = branch;
            this.elementUuid = elementUuid;
            this.eClass = eClass;
            this.feature = feature;
            this.changeType = changeType;
            this.origin = origin;
            this.from = from;
            this.to = to;
        }

        public String getBranch() { return branch; }
        public String getElementUuid() { return elementUuid; }
        public String getEClass() { return eClass; }
        public String getFeature() { return feature; }
        public String getChangeType() { return changeType; }
        public String getOrigin() { return origin; }
        public String getFrom() { return from; }
        public String getTo() { return to; }
    }

    @Override
    public String toString() {
        return "ConflictReview{" +
                "element=" + deletedElementEClass + " (uuid=" + deletedElementUuid + ")" +
                ", owners=" + detectedOwners +
                ", severity=" + (severityReport != null ? severityReport.getSeverity() : null) +
                ", history=" + (history != null ? history.size() : 0) +
                ", previews=" + (statePreviews != null ? statePreviews.size() : 0) +
                ", ancestorRecoverable=" + ancestorRecoverable +
                '}';
    }
}
