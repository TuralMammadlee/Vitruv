package tools.vitruv.framework.vsum.branch.agentic.tools;

import tools.vitruv.change.interaction.InteractionResultProvider;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolutionProvider;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeEngine;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/**
 * {@link ReplaySimulationContext} backed by the replay-based
 * {@link SemanticMergeEngine} (the components integrated from the paper's
 * repository). Given the base/ours/theirs commit SHAs and the change-propagation
 * setup, it constructs a fresh engine per simulation with a
 * {@link ConflictResolutionProvider} that applies exactly the candidate choice
 * (via {@link UpdateConflictAdapter}) and returns the engine's result.
 *
 * <p>All work happens on temp-dir/in-memory scratch state inside the engine; the
 * working tree is never touched, so simulations are side-effect free and safe to
 * run repeatedly while the agent deliberates.
 *
 * <p>"ours" is the target branch and "theirs" the source branch, matching the
 * engine's OURS/THEIRS convention.
 */
public final class EngineReplaySimulationContext implements ReplaySimulationContext {

    private final Path repoRoot;
    private final Collection<ChangePropagationSpecification> specs;
    private final InteractionResultProvider interactionProvider;
    private final String baseSha;
    private final String oursSha;
    private final String theirsSha;

    /**
     * @param repoRoot            the Git repository root.
     * @param specs               the change-propagation specifications for the VSUM.
     * @param interactionProvider the interaction result provider for replay.
     * @param baseSha             the merge base commit SHA.
     * @param oursSha             the target branch commit SHA.
     * @param theirsSha           the source branch commit SHA.
     */
    public EngineReplaySimulationContext(Path repoRoot,
                                         Collection<ChangePropagationSpecification> specs,
                                         InteractionResultProvider interactionProvider,
                                         String baseSha, String oursSha, String theirsSha) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        this.specs = Objects.requireNonNull(specs, "specs must not be null");
        this.interactionProvider = Objects.requireNonNull(interactionProvider,
                "interactionProvider must not be null");
        this.baseSha = Objects.requireNonNull(baseSha, "baseSha must not be null");
        this.oursSha = Objects.requireNonNull(oursSha, "oursSha must not be null");
        this.theirsSha = Objects.requireNonNull(theirsSha, "theirsSha must not be null");
    }

    @Override
    public SemanticMergeResult simulate(UpdateConflict conflict, boolean chooseSource) throws Exception {
        ConflictResolutionProvider provider = UpdateConflictAdapter.providerFor(conflict, chooseSource);
        SemanticMergeEngine engine =
                new SemanticMergeEngine(repoRoot, specs, interactionProvider, provider);
        return engine.merge(baseSha, oursSha, theirsSha);
    }
}
