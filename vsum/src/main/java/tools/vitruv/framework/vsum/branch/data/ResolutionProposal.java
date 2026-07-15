package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.Objects;

/**
 * A candidate resolution for an {@link UpdateConflict} produced by a
 * {@link tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor}, together
 * with a calibrated confidence score and a human-readable rationale.
 *
 * <p>This is the data structure behind the activity diagram's
 * "LLM/ML Proposes Candidate Resolution with Confidence Score" node. The
 * {@link #confidence()} value (in {@code [0,1]}) is what the resolver compares
 * against its acceptance threshold to decide between applying the proposal
 * automatically and routing it to a human for review.
 *
 * @param chosenEntry the change entry the advisor recommends keeping, never null.
 * @param confidence  calibrated confidence in {@code [0.0, 1.0]}.
 * @param rationale   short explanation of why this side was proposed, never null.
 * @param source      identifier of the advisor that produced it (for the audit trail).
 */
public record ResolutionProposal(
        SemanticChangeEntry chosenEntry,
        double confidence,
        String rationale,
        String source) {

    public ResolutionProposal {
        Objects.requireNonNull(chosenEntry, "chosenEntry must not be null");
        Objects.requireNonNull(rationale, "rationale must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (confidence < 0.0 || confidence > 1.0 || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("confidence must be in [0,1] but was " + confidence);
        }
    }

    /**
     * Returns {@code true} when this proposal's confidence is at or above the
     * given acceptance threshold.
     */
    public boolean meetsThreshold(double threshold) {
        return confidence >= threshold;
    }
}
