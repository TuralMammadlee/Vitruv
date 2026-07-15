package tools.vitruv.framework.vsum.branch.agentic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link ConflictResolutionAdvisor} that consults an ordered chain of
 * delegates and returns the first proposal produced.
 *
 * <p>The intended wiring puts the cheap, deterministic statistical baseline
 * ({@code AuditHistoryAdvisor}) first and the expensive local-LLM
 * {@link AgenticConflictResolutionAdvisor} second. That way the agent is only
 * invoked when the baseline has no opinion (typically cold start, before enough
 * history has accumulated for a signature), keeping the common path free of any
 * model call while still letting the agent contribute where the baseline is
 * silent. This is a straightforward composition over the same
 * {@link ConflictResolutionAdvisor} seam, so no change to {@code MergeManager}
 * or {@code UpdateConflictResolver} is required.
 *
 * <p>Ordering matters: a delegate that returns a proposal short-circuits the
 * chain. Wrap delegates in the order of increasing cost / decreasing trust.
 */
public final class CompositeConflictResolutionAdvisor implements ConflictResolutionAdvisor {

    private static final Logger LOGGER = LogManager.getLogger(CompositeConflictResolutionAdvisor.class);

    private final List<ConflictResolutionAdvisor> delegates;

    /**
     * Creates a composite over the given delegates, consulted in order.
     *
     * @param delegates the advisors to try in sequence; must be non-null and non-empty.
     */
    public CompositeConflictResolutionAdvisor(List<ConflictResolutionAdvisor> delegates) {
        Objects.requireNonNull(delegates, "delegates must not be null");
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("delegates must not be empty");
        }
        delegates.forEach(d -> Objects.requireNonNull(d, "delegate must not be null"));
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Convenience factory for the canonical two-tier chain: statistical baseline
     * first, agentic LLM fallback second.
     *
     * @param baseline the cheap deterministic advisor (e.g. {@code AuditHistoryAdvisor}).
     * @param agentic  the local-LLM fallback advisor.
     */
    public static CompositeConflictResolutionAdvisor baselineThenAgentic(
            ConflictResolutionAdvisor baseline, ConflictResolutionAdvisor agentic) {
        return new CompositeConflictResolutionAdvisor(List.of(baseline, agentic));
    }

    @Override
    public Optional<ResolutionProposal> propose(UpdateConflict conflict) {
        Objects.requireNonNull(conflict, "conflict must not be null");
        for (ConflictResolutionAdvisor delegate : delegates) {
            Optional<ResolutionProposal> proposal = delegate.propose(conflict);
            if (proposal.isPresent()) {
                LOGGER.debug("Conflict {} resolved by advisor source '{}' (confidence {})",
                        conflict.getElementUuid(), proposal.get().source(), proposal.get().confidence());
                return proposal;
            }
        }
        return Optional.empty();
    }
}
