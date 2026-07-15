package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;
import tools.vitruv.framework.vsum.branch.storage.ResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single conflict-resolution decision made during a merge.
 *
 * <p>Captured fields:
 * <ul>
 *   <li>{@link #timestamp} — ISO-8601 UTC instant when the decision was made.</li>
 *   <li>{@link #conflictType} — {@code "DELETION"}, {@code "UPDATE_AUTO_RESOLVED"},
 *       or {@code "UPDATE_MANUAL"}.</li>
 *   <li>{@link #elementUuid} — UUID of the model element the conflict is about.</li>
 *   <li>{@link #resolution} — name of the chosen policy or auto-resolution label.</li>
 *   <li>{@link #reason} — system-generated description of why this resolution was chosen.</li>
 *   <li>{@link #rationale} — optional free-text annotation provided by the human resolver;
 *       {@code null} for headless / auto-resolved decisions.</li>
 *   <li>{@link #sourceBranch} / {@link #targetBranch} — the merge branches.</li>
 *   <li>{@link #conflictSignature} / {@link #chosenSide} — machine-readable retrieval
 *       key and outcome for update conflicts (see below); {@code null} for deletions.</li>
 * </ul>
 *
 * <p>The signature ({@link ResolutionHistory#signature(UpdateConflict)}) and the
 * chosen side ({@code "source"} / {@code "target"}) make every persisted update
 * decision recoverable by
 * {@link tools.vitruv.framework.vsum.branch.storage.PersistedResolutionHistory}
 * without re-deriving anything from the free-text fields. This is what lets the
 * learned advisors (and the agentic LLM's history tool) feed themselves from the
 * audit trail on disk instead of requiring a manually maintained tally.
 *
 * <p>Instances are created via the factory methods {@link #forDeletion},
 * {@link #forUpdateAutoResolved} and {@link #forUpdateManual}. The class is
 * serialized to JSON by
 * {@link tools.vitruv.framework.vsum.branch.storage.AuditLogger}.
 */
public final class AuditLogEntry {

    /** Conflict-type discriminator for deletion decisions. */
    public static final String TYPE_DELETION = "DELETION";
    /** Conflict-type discriminator for automatically resolved update decisions. */
    public static final String TYPE_UPDATE_AUTO_RESOLVED = "UPDATE_AUTO_RESOLVED";
    /** Conflict-type discriminator for interactively resolved update decisions. */
    public static final String TYPE_UPDATE_MANUAL = "UPDATE_MANUAL";

    /** Value of {@link #chosenSide} when the source (incoming) branch's change was kept. */
    public static final String SIDE_SOURCE = "source";
    /** Value of {@link #chosenSide} when the target (current) branch's change was kept. */
    public static final String SIDE_TARGET = "target";

    private final String timestamp;
    private final String conflictType;
    private final String elementUuid;
    private final String resolution;
    private final String reason;
    private final String rationale;
    private final String sourceBranch;
    private final String targetBranch;
    private final String conflictSignature;
    private final String chosenSide;

    private AuditLogEntry(String timestamp, String conflictType, String elementUuid,
                          String resolution, String reason, String rationale,
                          String sourceBranch, String targetBranch,
                          String conflictSignature, String chosenSide) {
        this.timestamp    = Objects.requireNonNull(timestamp,    "timestamp must not be null");
        this.conflictType = Objects.requireNonNull(conflictType, "conflictType must not be null");
        this.elementUuid  = Objects.requireNonNull(elementUuid,  "elementUuid must not be null");
        this.resolution   = Objects.requireNonNull(resolution,   "resolution must not be null");
        this.reason       = Objects.requireNonNull(reason,       "reason must not be null");
        this.rationale    = rationale;  // nullable — null means no user annotation
        this.sourceBranch = Objects.requireNonNull(sourceBranch, "sourceBranch must not be null");
        this.targetBranch = Objects.requireNonNull(targetBranch, "targetBranch must not be null");
        this.conflictSignature = conflictSignature;  // nullable — update conflicts only
        this.chosenSide        = chosenSide;         // nullable — update conflicts only
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
                TYPE_DELETION,
                resolution.getConflict().getDeletedElementUuid(),
                resolution.getChosenPolicy().name(),
                resolution.getReason(),
                resolution.getRationale(),
                sourceBranch,
                targetBranch,
                null,   // deletion conflicts have no update signature
                null);
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
        UpdateConflict conflict = resolved.conflict();
        return new AuditLogEntry(
                Instant.now().toString(),
                TYPE_UPDATE_AUTO_RESOLVED,
                conflict.getElementUuid(),
                "AUTO: " + conflict.getFeatureName(),
                resolved.reason(),
                null,  // auto-resolved — no user annotation
                sourceBranch,
                targetBranch,
                ResolutionHistory.signature(conflict),
                sideOf(conflict, resolved.chosenEntry()));
    }

    /**
     * Creates an audit entry for an update conflict resolved interactively by a
     * human. Only resolved (non-deferred) decisions should be logged.
     *
     * @param resolution   the human decision; must be {@link ManualResolution#isResolved()}.
     * @param sourceBranch the source branch of the merge.
     * @param targetBranch the target branch of the merge.
     */
    public static AuditLogEntry forUpdateManual(ManualResolution resolution,
                                                String sourceBranch, String targetBranch) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (!resolution.isResolved()) {
            throw new IllegalArgumentException("deferred resolutions must not be audited as decisions");
        }
        UpdateConflict conflict = resolution.getConflict();
        return new AuditLogEntry(
                Instant.now().toString(),
                TYPE_UPDATE_MANUAL,
                conflict.getElementUuid(),
                resolution.getDecision().name() + ": " + conflict.getFeatureName(),
                "manual resolution: kept branch '" + resolution.getChosenBranch() + "'",
                resolution.getRationale(),
                sourceBranch,
                targetBranch,
                ResolutionHistory.signature(conflict),
                resolution.getDecision() == ManualResolution.Decision.ACCEPT_SOURCE
                        ? SIDE_SOURCE : SIDE_TARGET);
    }

    /**
     * Maps a winning change entry back to the side it came from. Reference
     * identity is checked first (the resolver hands back the conflict's own
     * entries); {@code equals} covers defensive copies. Returns {@code null}
     * when the entry matches neither side, so a foreign entry can never be
     * mis-recorded as evidence.
     */
    private static String sideOf(UpdateConflict conflict, SemanticChangeEntry chosenEntry) {
        if (chosenEntry == conflict.getSourceEntry() || chosenEntry.equals(conflict.getSourceEntry())) {
            return SIDE_SOURCE;
        }
        if (chosenEntry == conflict.getTargetEntry() || chosenEntry.equals(conflict.getTargetEntry())) {
            return SIDE_TARGET;
        }
        return null;
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

    /**
     * Returns the stable retrieval signature
     * ({@code eClass|feature|originPermutation}) of the update conflict this
     * decision resolved, or {@code null} for deletion decisions and for entries
     * written by versions that predate signature recording.
     */
    public String getConflictSignature() { return conflictSignature; }

    /**
     * Returns which side was kept — {@link #SIDE_SOURCE} or {@link #SIDE_TARGET} —
     * or {@code null} when the decision was not a two-sided update resolution.
     */
    public String getChosenSide() { return chosenSide; }

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
