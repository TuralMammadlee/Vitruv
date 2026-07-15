package tools.vitruv.framework.vsum.branch.storage;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.List;
import java.util.Objects;

/**
 * Read model over past conflict-resolution decisions, used by
 * {@link AuditHistoryAdvisor} to learn a project's de-facto conventions.
 *
 * <p>Each conflict is reduced to a stable {@link #signature(UpdateConflict)}
 * (element type plus feature plus origin permutation). For that signature the
 * history reports how often the source side versus the target side was kept by
 * previous resolutions. This is the retrieval step of a retrieval-augmented
 * advisor: instead of a black-box model, the system reuses the team's own
 * recorded behaviour as evidence.
 *
 * <p>Implementations may be backed by the in-memory tally
 * {@link InMemoryResolutionHistory} (for tests and short-lived sessions) or by
 * {@link PersistedResolutionHistory}, which parses the persisted
 * {@code .vitruvius/audit} JSON files so evidence survives restarts and is
 * shared across merges without any manual bookkeeping.
 */
public interface ResolutionHistory {

    /**
     * Observed counts of how often each side was kept for a conflict signature.
     *
     * @param sourceWins number of past decisions that kept the source side.
     * @param targetWins number of past decisions that kept the target side.
     */
    record Outcomes(int sourceWins, int targetWins) {
        public Outcomes {
            if (sourceWins < 0 || targetWins < 0) {
                throw new IllegalArgumentException("win counts must be non-negative");
            }
        }

        /** Total number of past decisions for the signature. */
        public int total() { return sourceWins + targetWins; }

        /** Empty history (no observations). */
        public static Outcomes none() { return new Outcomes(0, 0); }
    }

    /**
     * One recorded past decision for a conflict signature, including the
     * human-readable context that a retrieval-augmented advisor (or the agentic
     * LLM's history tool) can surface as evidence.
     *
     * @param timestamp  ISO-8601 instant of the decision.
     * @param chosenSide {@code "source"} or {@code "target"}.
     * @param reason     system-generated description of why this side won.
     * @param rationale  the human's free-text annotation, or {@code null}.
     */
    record RecordedDecision(String timestamp, String chosenSide, String reason, String rationale) {
        public RecordedDecision {
            Objects.requireNonNull(chosenSide, "chosenSide must not be null");
        }
    }

    /**
     * Returns the past outcomes recorded for the given conflict's signature.
     * Never null; returns {@link Outcomes#none()} when nothing is known.
     */
    Outcomes outcomesFor(UpdateConflict conflict);

    /**
     * Returns the most recent recorded decisions for the given conflict's
     * signature, newest first, up to {@code limit}. Implementations that only
     * keep aggregate tallies may return an empty list (the default).
     *
     * @param conflict the conflict whose comparable past decisions are requested.
     * @param limit    maximum number of decisions to return, {@code > 0}.
     */
    default List<RecordedDecision> decisionsFor(UpdateConflict conflict, int limit) {
        return List.of();
    }

    /**
     * Stable, branch-independent key for grouping comparable conflicts:
     * {@code eClass|feature|originPermutation}. Branch names are deliberately
     * excluded so evidence generalises across merges.
     */
    static String signature(UpdateConflict conflict) {
        Objects.requireNonNull(conflict, "conflict must not be null");
        return conflict.getEClass() + "|" + conflict.getFeatureName()
                + "|" + conflict.getOriginPermutation();
    }
}
