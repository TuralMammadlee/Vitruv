package tools.vitruv.framework.vsum.branch.data;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Tracks the clearance-denied escalation lifecycle for one deletion conflict:
 * awaiting owner response, owner denied / no response, or re-routed to a
 * senior role (diagram: {@code MERGE BLOCKED (Owner Escalated or No Response)}).
 *
 * <p>Persisted under {@code .vitruvius/owner-escalations/} by
 * {@link tools.vitruv.framework.vsum.branch.storage.OwnerEscalationStore}.
 */
public final class OwnerEscalation {

    /** Owner has been notified; decision pending. */
    public static final String AWAITING_OWNER = "AWAITING_OWNER";

    /** Owner denied or did not respond in time; re-routed to senior role. */
    public static final String ESCALATED_TO_SENIOR = "ESCALATED_TO_SENIOR";

    private final String timestamp;
    private final String elementUuid;
    private final String sourceBranch;
    private final String targetBranch;
    private final String status;
    private final String reason;
    private final List<String> assignees;
    private final String notifiedAt;

    private OwnerEscalation(String timestamp, String elementUuid, String sourceBranch,
                            String targetBranch, String status, String reason,
                            List<String> assignees, String notifiedAt) {
        this.timestamp = timestamp;
        this.elementUuid = elementUuid;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.status = status;
        this.reason = reason;
        this.assignees = assignees != null ? List.copyOf(assignees) : List.of();
        this.notifiedAt = notifiedAt;
    }

    public static OwnerEscalation awaitingOwner(String elementUuid, String sourceBranch,
                                                String targetBranch, List<String> ownerAssignees,
                                                String notifiedAt) {
        return new OwnerEscalation(
                Instant.now().toString(),
                Objects.requireNonNull(elementUuid),
                Objects.requireNonNull(sourceBranch),
                Objects.requireNonNull(targetBranch),
                AWAITING_OWNER,
                "Awaiting owner decision",
                ownerAssignees,
                notifiedAt);
    }

    public static OwnerEscalation escalatedToSenior(String elementUuid, String sourceBranch,
                                                    String targetBranch, String reason,
                                                    List<String> seniorAssignees) {
        return new OwnerEscalation(
                Instant.now().toString(),
                Objects.requireNonNull(elementUuid),
                Objects.requireNonNull(sourceBranch),
                Objects.requireNonNull(targetBranch),
                ESCALATED_TO_SENIOR,
                Objects.requireNonNull(reason),
                seniorAssignees,
                null);
    }

    public String getTimestamp() { return timestamp; }
    public String getElementUuid() { return elementUuid; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public List<String> getAssignees() { return assignees; }
    public String getNotifiedAt() { return notifiedAt; }

    public boolean isAwaitingOwner() {
        return AWAITING_OWNER.equals(status);
    }

    public boolean isEscalatedToSenior() {
        return ESCALATED_TO_SENIOR.equals(status);
    }

    @Override
    public String toString() {
        return "OwnerEscalation{element=" + elementUuid + ", status=" + status
                + ", assignees=" + assignees + '}';
    }
}
