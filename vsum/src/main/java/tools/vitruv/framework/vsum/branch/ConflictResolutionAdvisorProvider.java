package tools.vitruv.framework.vsum.branch;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Service-provider interface through which {@link MergeManager} discovers a
 * {@link ConflictResolutionAdvisor} at construction time, without depending on
 * any concrete advisor implementation.
 *
 * <p>Providers are looked up via {@link java.util.ServiceLoader}; an
 * implementation registers itself in
 * {@code META-INF/services/tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisorProvider}.
 * This inverts the dependency between the merge pipeline and the advisor
 * packages: the pipeline stays advisor-agnostic, while advisors (such as the
 * agentic local-LLM stack in {@code branch.agentic}) plug themselves in when
 * the repository opts in.
 *
 * <p>A provider decides per repository whether it applies — typically by
 * checking for its configuration file under {@code <repoRoot>/.vitruvius/config}.
 * Returning {@link Optional#empty()} means "not configured for this repository";
 * {@link MergeManager} then keeps its rule-only default,
 * {@link ConflictResolutionAdvisor#NONE}. A caller can always override the
 * discovered advisor through
 * {@link MergeManager#setResolutionAdvisor(ConflictResolutionAdvisor)}.
 */
public interface ConflictResolutionAdvisorProvider {

    /**
     * Creates the advisor for the given repository, or empty when this provider
     * is not configured/applicable there. Implementations must not throw: an
     * advisor that cannot be built must simply not be offered, so discovery can
     * never break a merge.
     *
     * @param repoRoot the Git repository root the {@link MergeManager} operates on.
     * @return the advisor to install, or empty to decline.
     */
    Optional<ConflictResolutionAdvisor> createAdvisor(Path repoRoot);
}
