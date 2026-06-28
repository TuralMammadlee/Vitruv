package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable payload sent to the detected owner(s) of a deletion conflict when
 * the current user's role cannot clear it (the "clearance denied" path of the
 * activity diagram, box {@code notify_owner}).
 *
 * <p>Bundles everything the owner needs to triage the conflict without opening
 * the model: the conflict identity (deleted element + branches), the
 * {@link AffectedElement affected elements} that would be destroyed, and the
 * computed risk score (origin-weighted impact, lost-update count and
 * {@link ConflictSeverity severity}).
 *
 * <p>Instances are serialized to JSON by
 * {@link tools.vitruv.framework.vsum.branch.storage.OwnerNotifier} under
 * {@code .vitruvius/notifications/}.
 */
public final class OwnerNotification {

    private final String timestamp;
    private final String sourceBranch;
    private final String targetBranch;
    private final Set<String> detectedOwners;
    private final String deletedElementUuid;
    private final String deletedElementEClass;
    private final String deletingBranch;
    private final String updatingBranch;
    private final List<AffectedElement> affectedElements;
    private final int riskScore;
    private final int lostUpdateCount;
    private final ConflictSeverity severity;

    private OwnerNotification(String timestamp, String sourceBranch, String targetBranch,
                             Set<String> detectedOwners, String deletedElementUuid,
                             String deletedElementEClass, String deletingBranch, String updatingBranch,
                             List<AffectedElement> affectedElements, int riskScore,
                             int lostUpdateCount, ConflictSeverity severity) {
        this.timestamp = timestamp;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.detectedOwners = detectedOwners;
        this.deletedElementUuid = deletedElementUuid;
        this.deletedElementEClass = deletedElementEClass;
        this.deletingBranch = deletingBranch;
        this.updatingBranch = updatingBranch;
        this.affectedElements = affectedElements;
        this.riskScore = riskScore;
        this.lostUpdateCount = lostUpdateCount;
        this.severity = severity;
    }

    /**
     * Builds a notification payload from a deletion conflict and the project
     * severity thresholds.
     *
     * @param conflict     the escalated deletion conflict.
     * @param thresholds   the project severity thresholds used to classify risk.
     * @param sourceBranch the merge source branch.
     * @param targetBranch the merge target branch.
     */
    public static OwnerNotification forConflict(DeletionConflict conflict, SeverityThresholds thresholds,
                                                String sourceBranch, String targetBranch) {
        Objects.requireNonNull(conflict, "conflict must not be null");
        SeverityThresholds effective = thresholds != null ? thresholds : SeverityThresholds.defaults();

        List<AffectedElement> affected = new ArrayList<>();
        for (SemanticChangeEntry update : conflict.getAffectedUpdates()) {
            affected.add(new AffectedElement(
                    update.getElementUuid(),
                    update.getEClass(),
                    update.getFeature(),
                    update.getChangeType() != null ? update.getChangeType().name() : null,
                    update.getOrigin() != null ? update.getOrigin().name() : null));
        }

        return new OwnerNotification(
                Instant.now().toString(),
                sourceBranch,
                targetBranch,
                conflict.getDetectedOwners(),
                conflict.getDeletedElementUuid(),
                conflict.getDeletedElementEClass(),
                conflict.getDeletingBranch(),
                conflict.getUpdatingBranch(),
                affected,
                conflict.getWeightedImpact(),
                conflict.getLostUpdateCount(),
                conflict.getSeverity(effective));
    }

    public String getTimestamp() { return timestamp; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public Set<String> getDetectedOwners() { return detectedOwners; }
    public String getDeletedElementUuid() { return deletedElementUuid; }
    public String getDeletedElementEClass() { return deletedElementEClass; }
    public String getDeletingBranch() { return deletingBranch; }
    public String getUpdatingBranch() { return updatingBranch; }
    public List<AffectedElement> getAffectedElements() { return affectedElements; }

    /** Origin-weighted impact score of accepting the deletion (the "risk score"). */
    public int getRiskScore() { return riskScore; }
    public int getLostUpdateCount() { return lostUpdateCount; }
    public ConflictSeverity getSeverity() { return severity; }

    /**
     * One affected model element referenced by a notification: the change that
     * would be destroyed if the deletion is accepted.
     */
    public static final class AffectedElement {
        private final String elementUuid;
        private final String eClass;
        private final String feature;
        private final String changeType;
        private final String origin;

        public AffectedElement(String elementUuid, String eClass, String feature,
                               String changeType, String origin) {
            this.elementUuid = elementUuid;
            this.eClass = eClass;
            this.feature = feature;
            this.changeType = changeType;
            this.origin = origin;
        }

        public String getElementUuid() { return elementUuid; }
        public String getEClass() { return eClass; }
        public String getFeature() { return feature; }
        public String getChangeType() { return changeType; }
        public String getOrigin() { return origin; }
    }

    @Override
    public String toString() {
        return "OwnerNotification{" +
                "element=" + deletedElementEClass + " (uuid=" + deletedElementUuid + ")" +
                ", owners=" + detectedOwners +
                ", riskScore=" + riskScore +
                ", severity=" + severity +
                ", lostUpdates=" + lostUpdateCount +
                '}';
    }
}
