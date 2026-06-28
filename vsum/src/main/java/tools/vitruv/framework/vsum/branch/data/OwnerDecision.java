package tools.vitruv.framework.vsum.branch.data;

import java.time.Instant;
import java.util.Objects;

/**
 * Records an owner's approve/deny decision on an escalated deletion conflict
 * (activity-diagram diamond {@code d_owner} and the {@code owner_rationale} /
 * {@code MERGE BLOCKED} outcomes).
 *
 * <p>Persisted by {@link tools.vitruv.framework.vsum.branch.storage.OwnerDecisionStore}
 * under {@code .vitruvius/owner-decisions/}.
 */
public final class OwnerDecision {

    /** The owner approved the conflict resolution. */
    public static final String APPROVE = "APPROVE";

    /** The owner denied the conflict resolution; merge stays blocked. */
    public static final String DENY = "DENY";

    private final String timestamp;
    private final String elementUuid;
    private final String ownerId;
    private final String decision;
    private final String rationale;
    private final String sourceBranch;
    private final String targetBranch;

    private OwnerDecision(String timestamp, String elementUuid, String ownerId,
                          String decision, String rationale, String sourceBranch, String targetBranch) {
        this.timestamp = timestamp;
        this.elementUuid = elementUuid;
        this.ownerId = ownerId;
        this.decision = decision;
        this.rationale = rationale;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
    }

    /**
     * Creates a new owner decision record.
     *
     * @param elementUuid  UUID of the conflicting deleted element.
     * @param ownerId      normalized email of the deciding owner.
     * @param approve      {@code true} for APPROVE, {@code false} for DENY.
     * @param rationale    optional free-text explanation from the owner.
     * @param sourceBranch the merge source branch.
     * @param targetBranch the merge target branch.
     */
    public static OwnerDecision of(String elementUuid, String ownerId, boolean approve,
                                   String rationale, String sourceBranch, String targetBranch) {
        return new OwnerDecision(
                Instant.now().toString(),
                Objects.requireNonNull(elementUuid, "elementUuid must not be null"),
                Objects.requireNonNull(ownerId, "ownerId must not be null"),
                approve ? APPROVE : DENY,
                rationale,
                sourceBranch,
                targetBranch);
    }

    public String getTimestamp() { return timestamp; }
    public String getElementUuid() { return elementUuid; }
    public String getOwnerId() { return ownerId; }
    public String getDecision() { return decision; }
    public String getRationale() { return rationale; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }

    public boolean isApproved() {
        return APPROVE.equals(decision);
    }

    @Override
    public String toString() {
        return "OwnerDecision{" +
                "element=" + elementUuid +
                ", owner=" + ownerId +
                ", decision=" + decision +
                ", rationale=" + (rationale != null ? "'" + rationale + "'" : "none") +
                '}';
    }
}
