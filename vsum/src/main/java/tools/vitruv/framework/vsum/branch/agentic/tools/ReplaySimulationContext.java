package tools.vitruv.framework.vsum.branch.agentic.tools;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;

/**
 * Supplies the ambient information needed to run a sandboxed replay of a
 * candidate resolution: the three commit states of the merge and the machinery
 * to replay them. Implementations own the (heavyweight) merge engine and the
 * base/ours/theirs commit SHAs; the agent tool only asks "try this choice and
 * tell me what happens".
 *
 * <p>This exists because an {@link UpdateConflict} alone does not carry the Git
 * commit identity or the change-propagation setup required to replay. During a
 * real branch merge the orchestrator has all of that and can provide a context;
 * in environments that do not (unit fixtures, the offline evaluation harness),
 * no context is supplied and the {@code simulate_replay} tool simply reports that
 * simulation is unavailable, leaving the rest of the agent fully functional.
 */
public interface ReplaySimulationContext {

    /**
     * Replays the merge with the focal conflict resolved by the given choice,
     * on isolated scratch state (never the working tree), and returns the result.
     *
     * @param conflict     the production conflict whose resolution is under test.
     * @param chooseSource {@code true} to keep the source side, {@code false} for the target side.
     * @return the resulting {@link SemanticMergeResult}.
     * @throws Exception if the replay cannot be performed.
     */
    SemanticMergeResult simulate(UpdateConflict conflict, boolean chooseSource) throws Exception;
}
