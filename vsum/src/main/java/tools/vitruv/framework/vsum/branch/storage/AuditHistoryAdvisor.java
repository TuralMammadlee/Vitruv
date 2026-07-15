package tools.vitruv.framework.vsum.branch.storage;

import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.Objects;
import java.util.Optional;

/**
 * A retrieval-augmented {@link ConflictResolutionAdvisor} that learns from the
 * project's own resolution history instead of a pre-trained black-box model.
 *
 * <p>For each conflict it retrieves the past decisions recorded for the same
 * {@link ResolutionHistory#signature(UpdateConflict) signature} and models the
 * probability that the source side should win as a Beta-Bernoulli posterior:
 *
 * <pre>
 *   p(source) = (sourceWins + alpha) / (sourceWins + targetWins + alpha + beta)
 * </pre>
 *
 * <p>The side with the higher posterior probability is proposed, and the
 * confidence is {@code max(p, 1 - p)} — the posterior mass on the chosen side.
 * A {@code minObservations} guard suppresses proposals during cold start so the
 * advisor stays silent (returns {@link Optional#empty()}) until it has seen
 * enough evidence, at which point the conflict falls through to a human.
 *
 * <p>This is deterministic, requires no external service, and is auditable: the
 * rationale string records the exact counts behind every proposal. It is also a
 * drop-in baseline — the same {@link ConflictResolutionAdvisor} seam accepts a
 * calibrated classifier, a conformal-prediction wrapper, or an LLM later without
 * any change to the merge pipeline.
 */
public final class AuditHistoryAdvisor implements ConflictResolutionAdvisor {

    private static final String SOURCE_ID = "audit-history(beta)";

    private final ResolutionHistory history;
    private final double priorAlpha;
    private final double priorBeta;
    private final int minObservations;

    /**
     * Creates an advisor with a uniform Beta(1,1) prior that stays silent until
     * it has at least three observations for a signature.
     */
    public AuditHistoryAdvisor(ResolutionHistory history) {
        this(history, 1.0, 1.0, 3);
    }

    /**
     * Creates an advisor with explicit prior and cold-start configuration.
     *
     * @param history         the source of past outcomes, never null.
     * @param priorAlpha      Beta prior pseudo-count for "source wins" ({@code > 0}).
     * @param priorBeta       Beta prior pseudo-count for "target wins" ({@code > 0}).
     * @param minObservations minimum recorded decisions before a proposal is made.
     */
    public AuditHistoryAdvisor(ResolutionHistory history, double priorAlpha,
                               double priorBeta, int minObservations) {
        this.history = Objects.requireNonNull(history, "history must not be null");
        if (priorAlpha <= 0 || priorBeta <= 0) {
            throw new IllegalArgumentException("Beta prior counts must be positive");
        }
        if (minObservations < 0) {
            throw new IllegalArgumentException("minObservations must be non-negative");
        }
        this.priorAlpha = priorAlpha;
        this.priorBeta = priorBeta;
        this.minObservations = minObservations;
    }

    @Override
    public Optional<ResolutionProposal> propose(UpdateConflict conflict) {
        Objects.requireNonNull(conflict, "conflict must not be null");

        ResolutionHistory.Outcomes outcomes = history.outcomesFor(conflict);
        int observed = outcomes.total();
        if (observed < minObservations) {
            return Optional.empty();
        }

        double pSource = (outcomes.sourceWins() + priorAlpha)
                / (observed + priorAlpha + priorBeta);
        boolean preferSource = pSource >= 0.5;
        double confidence = Math.max(pSource, 1.0 - pSource);

        String rationale = String.format(
                "history for [%s]: source kept %d, target kept %d; posterior p(source)=%.3f",
                ResolutionHistory.signature(conflict),
                outcomes.sourceWins(), outcomes.targetWins(), pSource);

        return Optional.of(new ResolutionProposal(
                preferSource ? conflict.getSourceEntry() : conflict.getTargetEntry(),
                confidence,
                rationale,
                SOURCE_ID));
    }
}
