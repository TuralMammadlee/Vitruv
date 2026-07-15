package tools.vitruv.framework.vsum.branch;

import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.Optional;

/**
 * Strategy interface for a learned / model-based conflict advisor.
 *
 * <p>This is the dedicated extension point for the activity diagram's
 * "LLM/ML-assisted resolution" path. It sits between the deterministic
 * {@link DomainValidator} tier and the interactive
 * {@link ConflictResolutionStrategy} tier: when no rule applies, the advisor may
 * still propose a side together with a calibrated confidence score. The resolver
 * only applies the proposal automatically when its confidence clears a
 * configured threshold; otherwise the conflict is routed to a human.
 *
 * <p>The interface is intentionally model-agnostic so that any of the following
 * can be dropped in behind it without touching the merge pipeline:
 * <ul>
 *   <li>a retrieval-augmented advisor over the project's own audit log
 *       (see {@link tools.vitruv.framework.vsum.branch.storage.AuditHistoryAdvisor}),</li>
 *   <li>a calibrated gradient-boosted / random-forest classifier over the
 *       structured origin and severity features,</li>
 *   <li>a conformal-prediction wrapper that turns any base model's score into a
 *       statistically valid confidence, or</li>
 *   <li>an LLM that reads the conflict context and returns a self-consistency
 *       confidence.</li>
 * </ul>
 *
 * <p>Implementations must be side-effect free and must not throw checked
 * exceptions. Returning {@link Optional#empty()} means "no opinion", which lets
 * the conflict fall through to manual resolution.
 */
@FunctionalInterface
public interface ConflictResolutionAdvisor {

    /**
     * Proposes a candidate resolution for the given conflict, or empty if the
     * advisor has no confident opinion.
     *
     * @param conflict the conflict to evaluate, never null.
     * @return a scored proposal, or empty to fall through to manual resolution.
     */
    Optional<ResolutionProposal> propose(UpdateConflict conflict);

    /**
     * No-op advisor: never proposes anything. This is the default used by
     * {@link MergeManager} so that, until a model is wired in, behaviour is
     * identical to the rule-only pipeline.
     */
    ConflictResolutionAdvisor NONE = conflict -> Optional.empty();
}
