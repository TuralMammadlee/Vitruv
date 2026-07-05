package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single conflict-resolution decision made during a merge.
 *
 * <p>Captured fields:
 * <ul>
 *   <li>{@link #timestamp} — ISO-8601 UTC instant when the decision was made.</li>
 *   <li>{@link #conflictType} — {@code "DELETION"} or {@code "UPDATE_AUTO_RESOLVED"}.</li>
 *   <li>{@link #elementUuid} — UUID of the model element the conflict is about.</li>
 *   <li>{@link #resolution} — name of the chosen policy or auto-resolution label.</li>
 *   <li>{@link #reason} — system-generated description of why this resolution was chosen.</li>
 *   <li>{@link #rationale} — optional free-text annotation provided by the human resolver;
 *       {@code null} for headless / auto-resolved decisions.</li>
 *   <li>{@link #sourceBranch} / {@link #targetBranch} — the merge branches.</li>
 * </ul>
 *
 * <p>Instances are created via the factory methods {@link #forDeletion} and
 * {@link #forUpdateAutoResolved}. The class is serialized to JSON by
 * {@link tools.vitruv.framework.vsum.branch.storage.AuditLogger}.
 */
public final class AuditLogEntry {

    private final String timestamp;
    private final String conflictType;
    private final String elementUuid;
    private final String resolution;
    private final String reason;
    private final String rationale;
    private final String sourceBranch;
    private final String targetBranch;

    private AuditLogEntry(String timestamp, String conflictType, String elementUuid,
                          String resolution, String reason, String rationale,
                          String sourceBranch, String targetBranch) {
        this.timestamp    = Objects.requireNonNull(timestamp,    "timestamp must not be null");
        this.conflictType = Objects.requireNonNull(conflictType, "conflictType must not be null");
        this.elementUuid  = Objects.requireNonNull(elementUuid,  "elementUuid must not be null");
        this.resolution   = Objects.requireNonNull(resolution,   "resolution must not be null");
        this.reason       = Objects.requireNonNull(reason,       "reason must not be null");
        this.rationale    = rationale;  // nullable — null means no user annotation
        this.sourceBranch = Objects.requireNonNull(sourceBranch, "sourceBranch must not be null");
        this.targetBranch = Objects.requireNonNull(targetBranch, "targetBranch must not be null");
    }

    /**
     * Creates an audit entry from a deletion-conflict resolution.
     *
     * @param resolution   the resolution chosen by the user (or headless fallback).
     * @param sourceBranch the source branch of the merge.
     * @param targetBranch the target branch of the merge.
     */
    public static AuditLogEntry forDeletion(DeletionConflictResolver.Resolution resolution,
                                            String sourceBranch, String targetBranch) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        return new AuditLogEntry(
                Instant.now().toString(),
                "DELETION",
                resolution.getConflict().getDeletedElementUuid(),
                resolution.getChosenPolicy().name(),
                resolution.getReason(),
                resolution.getRationale(),
                sourceBranch,
                targetBranch);
    }

    /**
     * Creates an audit entry for an automatically resolved update conflict.
     *
     * @param resolved     the auto-resolution record from {@link AutoResolutionOutcome}.
     * @param sourceBranch the source branch of the merge.
     * @param targetBranch the target branch of the merge.
     */
    public static AuditLogEntry forUpdateAutoResolved(AutoResolutionOutcome.ResolvedConflict resolved,
                                                      String sourceBranch, String targetBranch) {
        Objects.requireNonNull(resolved, "resolved must not be null");
        return new AuditLogEntry(
                Instant.now().toString(),
                "UPDATE_AUTO_RESOLVED",
                resolved.conflict().getElementUuid(),
                "AUTO: " + resolved.conflict().getFeatureName(),
                resolved.reason(),
                null,  // auto-resolved — no user annotation
                sourceBranch,
                targetBranch);
    }

    /**
     * Creates an audit entry when an owner notification artifact is written
     * during the clearance-denied escalation path.
     */
    public static AuditLogEntry forOwnerNotification(OwnerNotification notification,
                                                     String sourceBranch, String targetBranch) {
        Objects.requireNonNull(notification, "notification must not be null");
        return new AuditLogEntry(
                Instant.now().toString(),
                "OWNER_NOTIFICATION",
                notification.getDeletedElementUuid(),
                "NOTIFIED: " + notification.getDetectedOwners(),
                "Risk score " + notification.getRiskScore()
                        + ", severity " + notification.getSeverity()
                        + ", " + notification.getLostUpdateCount() + " lost update(s)",
                null,
                sourceBranch,
                targetBranch);
    }

    /**
     * Creates an audit entry when a conflict review artifact is written for
     * the owner to inspect.
     */
    public static AuditLogEntry forOwnerReview(ConflictReview review,
                                               String sourceBranch, String targetBranch) {
        Objects.requireNonNull(review, "review must not be null");
        return new AuditLogEntry(
                Instant.now().toString(),
                "OWNER_REVIEW",
                review.getDeletedElementUuid(),
                "REVIEW: severity " + review.getSeverityReport().getSeverity(),
                review.getHistory().size() + " history entry/entries, "
                        + review.getStatePreviews().size() + " preview(s), "
                        + "ancestorRecoverable=" + review.isAncestorRecoverable(),
                null,
                sourceBranch,
                targetBranch);
    }

    /**
     * Creates an audit entry when an owner records an approve/deny decision.
     */
    public static AuditLogEntry forOwnerDecision(OwnerDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        return new AuditLogEntry(
                decision.getTimestamp(),
                "OWNER_DECISION",
                decision.getElementUuid(),
                decision.getDecision(),
                "Owner " + decision.getOwnerId() + " " + decision.getDecision().toLowerCase()
                        + " the conflict resolution",
                decision.getRationale(),
                decision.getSourceBranch(),
                decision.getTargetBranch());
    }

    /**
     * Creates an audit entry when a conflict is re-routed to a senior role
     * after owner deny or no response.
     */
    public static AuditLogEntry forOwnerEscalation(OwnerEscalation escalation) {
        Objects.requireNonNull(escalation, "escalation must not be null");
        return new AuditLogEntry(
                escalation.getTimestamp(),
                "OWNER_ESCALATION",
                escalation.getElementUuid(),
                escalation.getStatus(),
                escalation.getReason() + " -> assignees " + escalation.getAssignees(),
                null,
                escalation.getSourceBranch(),
                escalation.getTargetBranch());
    }

    /**
     * Creates an audit entry when a merge is blocked because no owner approved
     * the escalated conflict (or the owner denied it).
     */
    public static AuditLogEntry forMergeBlocked(String elementUuid, String reason,
                                                String sourceBranch, String targetBranch) {
        return new AuditLogEntry(
                Instant.now().toString(),
                "MERGE_BLOCKED",
                elementUuid,
                "BLOCKED",
                reason,
                null,
                sourceBranch,
                targetBranch);
    }

    public String getTimestamp()    { return timestamp; }
    public String getConflictType() { return conflictType; }
    public String getElementUuid()  { return elementUuid; }
    public String getResolution()   { return resolution; }
    public String getReason()       { return reason; }

    /**
     * Returns the optional human-provided rationale for this decision, or
     * {@code null} if the decision was made headlessly or the user provided
     * no comment.
     */
    public String getRationale()    { return rationale; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }

    @Override
    public String toString() {
        return "AuditLogEntry{" +
                "type=" + conflictType +
                ", element=" + elementUuid +
                ", resolution=" + resolution +
                ", rationale=" + (rationale != null ? "'" + rationale + "'" : "none") +
                '}';
    }
}
