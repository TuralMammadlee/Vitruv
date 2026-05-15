package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.DomainValidator;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves update-vs-update conflicts using a three-tier strategy that
 * avoids involving the UI whenever a deterministic choice can be made.
 *
 * <h3>Tier 1 — Origin rule (highest priority)</h3>
 * <p>If one side is {@link ChangeOrigin#ORIGINAL} (human) and the other is
 * {@link ChangeOrigin#CONSEQUENTIAL} (engine-generated), the ORIGINAL side
 * wins automatically. This encodes the core Vitruvius rule "favor human
 * intent over propagated side-effects." The UI is never shown for these
 * conflicts.
 *
 * <h3>Tier 2 — Domain validator</h3>
 * <p>For conflicts where both sides share the same origin (O-O or C-C) or
 * the origin is unknown, the supplied {@link DomainValidator} is consulted.
 * If it returns a preferred entry, that entry is applied automatically.
 * Use this tier to encode metamodel- or project-specific rules (e.g.,
 * "schema version from the source branch always wins").
 *
 * <h3>Tier 3 — Manual resolution</h3>
 * <p>Any conflict not handled by the first two tiers is placed in
 * {@link AutoResolutionOutcome#getUnresolved()} for UI-level handling.
 *
 * <p>This class is stateless. A new instance can be created per resolution
 * session or shared across sessions when the {@link DomainValidator} is
 * thread-safe.
 *
 * @see DeletionConflictResolver
 * @see DomainValidator
 * @see AutoResolutionOutcome
 */
public class UpdateConflictResolver {

    private static final Logger LOGGER = LogManager.getLogger(UpdateConflictResolver.class);

    private final DomainValidator domainValidator;

    /**
     * Creates a resolver with the given domain validator.
     * Pass {@link DomainValidator#NONE} to skip the domain-validator tier.
     *
     * @param domainValidator the domain-specific resolution strategy, must not be null.
     */
    public UpdateConflictResolver(DomainValidator domainValidator) {
        this.domainValidator = Objects.requireNonNull(domainValidator,
                "domainValidator must not be null");
    }

    /**
     * Applies the three-tier resolution strategy to every conflict in the list.
     *
     * @param conflicts the update conflicts detected during the merge; may be empty, must not be null.
     * @return an outcome describing what was auto-resolved and what still needs UI.
     */
    public AutoResolutionOutcome resolve(List<UpdateConflict> conflicts) {
        Objects.requireNonNull(conflicts, "conflicts must not be null");
        if (conflicts.isEmpty()) {
            return AutoResolutionOutcome.empty();
        }

        List<AutoResolutionOutcome.ResolvedConflict> autoResolved = new ArrayList<>();
        List<UpdateConflict> unresolved = new ArrayList<>();

        for (UpdateConflict conflict : conflicts) {

            // Tier 1: origin rule — the human change (ORIGINAL) wins over the engine change (CONSEQUENTIAL)
            if (conflict.getOriginPermutation().isMixedOrigin()) {
                SemanticChangeEntry chosen = conflict.getPreferredEntry();
                autoResolved.add(new AutoResolutionOutcome.ResolvedConflict(
                        conflict, chosen, "origin rule: ORIGINAL over CONSEQUENTIAL"));
                LOGGER.info("Auto-resolved {}.{} [{}]: preferred branch '{}'",
                        conflict.getEClass(), conflict.getFeatureName(),
                        conflict.getOriginPermutation(), conflict.getPreferredBranch());
                continue;
            }

            // Tier 2: domain validator — check for a project/metamodel-specific default
            Optional<SemanticChangeEntry> suggestion = domainValidator.suggestResolution(conflict);
            if (suggestion.isPresent()) {
                autoResolved.add(new AutoResolutionOutcome.ResolvedConflict(
                        conflict, suggestion.get(), "domain-validator default"));
                LOGGER.info("Auto-resolved {}.{} [{}] via domain validator",
                        conflict.getEClass(), conflict.getFeatureName(),
                        conflict.getOriginPermutation());
                continue;
            }

            // Tier 3: no automatic rule applies — needs UI
            unresolved.add(conflict);
            LOGGER.info("Manual resolution required for {}.{} [severity={}, permutation={}]",
                    conflict.getEClass(), conflict.getFeatureName(),
                    conflict.getSeverity(), conflict.getOriginPermutation());
        }

        AutoResolutionOutcome outcome = AutoResolutionOutcome.of(autoResolved, unresolved);
        LOGGER.info("Update conflict resolution complete: {}/{} auto-resolved, {} require manual input",
                autoResolved.size(), conflicts.size(), unresolved.size());
        return outcome;
    }
}
