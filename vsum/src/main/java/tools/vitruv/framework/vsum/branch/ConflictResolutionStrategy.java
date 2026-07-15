package tools.vitruv.framework.vsum.branch;

import tools.vitruv.framework.vsum.branch.data.ManualResolution;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

/**
 * Pluggable interface for the interactive ("intervention required") branch of
 * the conflict-resolution activity diagram.
 *
 * <p>After {@link tools.vitruv.framework.vsum.branch.storage.UpdateConflictResolver}
 * has applied its automatic tiers (origin rule, domain validator, advisor), any
 * conflict left in
 * {@link tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome#getUnresolved()}
 * is handed to a {@code ConflictResolutionStrategy} so a human can decide.
 *
 * <p>Implementations decide <em>how</em> to present the choice. The default
 * {@link tools.vitruv.framework.vsum.branch.storage.ConsoleConflictResolutionStrategy}
 * is severity-aware: lower-severity conflicts offer a simple accept-source /
 * accept-target choice, while {@link tools.vitruv.framework.vsum.branch.data.ConflictSeverity#HIGH}
 * and {@link tools.vitruv.framework.vsum.branch.data.ConflictSeverity#CRITICAL}
 * conflicts force a detailed inspection and require a rationale before a side
 * can be accepted.
 *
 * <p>A strategy never mutates the model; it only returns a {@link ManualResolution}
 * describing the decision. Applying the winning change is the caller's job.
 */
@FunctionalInterface
public interface ConflictResolutionStrategy {

    /**
     * Presents the conflict for human resolution and returns the decision.
     *
     * @param conflict the conflict to resolve, never null.
     * @return the human's decision, or a deferral when no choice was made.
     */
    ManualResolution resolve(UpdateConflict conflict);

    /**
     * Presents the conflict together with an advisory proposal that did not
     * clear the auto-apply confidence threshold (the activity diagram's
     * "confidence below threshold" branch, where the user still sees the
     * conflict history alongside the advisor's suggestion).
     *
     * <p>The default implementation ignores the hint and delegates to
     * {@link #resolve(UpdateConflict)}, so existing strategies keep working
     * unchanged; interactive strategies should override this to display the
     * proposal.
     *
     * @param conflict         the conflict to resolve, never null.
     * @param advisoryProposal the low-confidence advisor proposal, never null.
     * @return the human's decision, or a deferral when no choice was made.
     */
    default ManualResolution resolve(UpdateConflict conflict, ResolutionProposal advisoryProposal) {
        return resolve(conflict);
    }

    /**
     * Reviews an advisor proposal whose confidence <em>cleared</em> the
     * auto-apply threshold — the activity diagram's "User Reviews LLM Proposal
     * &amp; Confirms or Overrides" step.
     *
     * <p>The default implementation confirms the proposal, which preserves the
     * headless auto-resolution behaviour: a confident proposal is applied
     * without a UI round-trip (the threshold is the safety gate). Interactive
     * strategies should override this to let the user confirm, override to the
     * other side, or defer; returning a
     * {@link ManualResolution#deferred(UpdateConflict, String) deferral} routes
     * the conflict to full manual resolution instead.
     *
     * @param conflict the conflict the proposal is about, never null.
     * @param proposal the confident advisor proposal, never null.
     * @return the (possibly overriding) decision.
     */
    default ManualResolution reviewProposal(UpdateConflict conflict, ResolutionProposal proposal) {
        return proposesSource(conflict, proposal)
                ? ManualResolution.acceptSource(conflict, proposal.rationale())
                : ManualResolution.acceptTarget(conflict, proposal.rationale());
    }

    /**
     * Determines which side a proposal picked, giving reference identity
     * priority over value equality. Advisors return one of the conflict's own
     * entries, so the reference check is exact; the {@code equals} fallback only
     * matters for reconstructed entries and never mislabels a side when the two
     * entries are equal by value (in which case either label yields the same
     * value anyway).
     */
    private static boolean proposesSource(UpdateConflict conflict, ResolutionProposal proposal) {
        SemanticChangeEntry chosen = proposal.chosenEntry();
        if (chosen == conflict.getSourceEntry()) {
            return true;
        }
        if (chosen == conflict.getTargetEntry()) {
            return false;
        }
        return chosen.equals(conflict.getSourceEntry());
    }

    /**
     * Default strategy that defers every conflict. Installed by
     * {@link MergeManager} when no interactive strategy has been configured, so
     * that unresolved conflicts are simply returned to the caller unchanged.
     * (As a lambda it inherits the default {@link #reviewProposal} behaviour:
     * confident advisor proposals are auto-confirmed.)
     */
    ConflictResolutionStrategy DEFER_ALL = conflict ->
            ManualResolution.deferred(conflict, "no interactive resolution strategy configured");
}
