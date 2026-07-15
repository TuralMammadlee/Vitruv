package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.ConflictResolutionStrategy;
import tools.vitruv.framework.vsum.branch.DomainValidator;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.ManualResolution;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h3>Tier 3 — Learned advisor (optional)</h3>
 * <p>If neither deterministic tier applies, the supplied
 * {@link ConflictResolutionAdvisor} may propose a side together with a
 * calibrated confidence score. A proposal whose confidence is at or above
 * {@code confidenceThreshold} is put before the {@code proposalReviewer} — the
 * activity diagram's "User Reviews LLM Proposal &amp; Confirms or Overrides"
 * step. The reviewer's confirmation (or override) is applied automatically;
 * a deferral, or a confidence below the threshold, routes the conflict to
 * manual resolution with the proposal attached as an advisory hint. Pass
 * {@link ConflictResolutionAdvisor#NONE} to disable this tier.
 *
 * <h3>Tier 4 — Manual resolution</h3>
 * <p>Any conflict not handled by the earlier tiers is placed in
 * {@link AutoResolutionOutcome#getUnresolved()} for UI-level handling, together
 * with any advisory proposal via
 * {@link AutoResolutionOutcome#getAdvisoryProposal(UpdateConflict)}.
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

    /** Default confidence required before a learned advisor's proposal is auto-applied. */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.8;

    private final DomainValidator domainValidator;
    private final ConflictResolutionAdvisor advisor;
    private final double confidenceThreshold;
    private final ConflictResolutionStrategy proposalReviewer;

    /**
     * Creates a resolver with the given domain validator and no learned advisor.
     * Pass {@link DomainValidator#NONE} to skip the domain-validator tier.
     *
     * @param domainValidator the domain-specific resolution strategy, must not be null.
     */
    public UpdateConflictResolver(DomainValidator domainValidator) {
        this(domainValidator, ConflictResolutionAdvisor.NONE, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    /**
     * Creates a resolver with a domain validator and a learned advisor tier
     * whose confident proposals are auto-confirmed (no interactive review).
     *
     * @param domainValidator     the deterministic domain rule, must not be null
     *                            (use {@link DomainValidator#NONE} to disable).
     * @param advisor             the learned/model-based advisor, must not be null
     *                            (use {@link ConflictResolutionAdvisor#NONE} to disable).
     * @param confidenceThreshold minimum confidence in {@code [0,1]} for an
     *                            advisor proposal to be applied automatically.
     */
    public UpdateConflictResolver(DomainValidator domainValidator,
                                  ConflictResolutionAdvisor advisor,
                                  double confidenceThreshold) {
        this(domainValidator, advisor, confidenceThreshold, ConflictResolutionStrategy.DEFER_ALL);
    }

    /**
     * Creates a resolver with a domain validator, a learned advisor tier, and a
     * reviewer for the advisor's confident proposals.
     *
     * @param domainValidator     the deterministic domain rule, must not be null
     *                            (use {@link DomainValidator#NONE} to disable).
     * @param advisor             the learned/model-based advisor, must not be null
     *                            (use {@link ConflictResolutionAdvisor#NONE} to disable).
     * @param confidenceThreshold minimum confidence in {@code [0,1]} for an
     *                            advisor proposal to reach the reviewer.
     * @param proposalReviewer    strategy whose
     *                            {@link ConflictResolutionStrategy#reviewProposal reviewProposal}
     *                            confirms or overrides confident proposals; must
     *                            not be null. Non-interactive strategies (and
     *                            {@link ConflictResolutionStrategy#DEFER_ALL})
     *                            auto-confirm.
     */
    public UpdateConflictResolver(DomainValidator domainValidator,
                                  ConflictResolutionAdvisor advisor,
                                  double confidenceThreshold,
                                  ConflictResolutionStrategy proposalReviewer) {
        this.domainValidator = Objects.requireNonNull(domainValidator,
                "domainValidator must not be null");
        this.advisor = Objects.requireNonNull(advisor, "advisor must not be null");
        if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException("confidenceThreshold must be in [0,1]");
        }
        this.confidenceThreshold = confidenceThreshold;
        this.proposalReviewer = Objects.requireNonNull(proposalReviewer,
                "proposalReviewer must not be null");
    }

    /**
     * Applies the tiered resolution strategy to every conflict in the list.
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
        Map<UpdateConflict, ResolutionProposal> advisoryProposals = new LinkedHashMap<>();

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

            // Tier 3: learned advisor — confident proposals go through review
            Optional<ResolutionProposal> proposal = advisor.propose(conflict);
            if (proposal.isPresent() && proposal.get().meetsThreshold(confidenceThreshold)) {
                ResolutionProposal p = proposal.get();
                ManualResolution review = proposalReviewer.reviewProposal(conflict, p);
                if (review.isResolved()) {
                    autoResolved.add(new AutoResolutionOutcome.ResolvedConflict(
                            conflict, review.getChosenEntry(), reviewReason(conflict, p, review)));
                    LOGGER.info("Resolved {}.{} [{}] via advisor {} (confidence={}, kept '{}')",
                            conflict.getEClass(), conflict.getFeatureName(),
                            conflict.getOriginPermutation(), p.source(), p.confidence(),
                            review.getChosenBranch());
                    continue;
                }
                LOGGER.info("Reviewer deferred advisor proposal for {}.{}; routing to manual",
                        conflict.getEClass(), conflict.getFeatureName());
            } else {
                proposal.ifPresent(p -> LOGGER.info(
                        "Advisor proposed {}.{} with confidence {} (below threshold {}); routing to manual",
                        conflict.getEClass(), conflict.getFeatureName(), p.confidence(), confidenceThreshold));
            }

            // Tier 4: no automatic rule applies — needs UI. Any advisor proposal
            // travels along as a hint for the interactive strategy.
            proposal.ifPresent(p -> advisoryProposals.put(conflict, p));
            unresolved.add(conflict);
            LOGGER.info("Manual resolution required for {}.{} [severity={}, permutation={}]",
                    conflict.getEClass(), conflict.getFeatureName(),
                    conflict.getSeverity(), conflict.getOriginPermutation());
        }

        AutoResolutionOutcome outcome = AutoResolutionOutcome.of(autoResolved, unresolved, advisoryProposals);
        LOGGER.info("Update conflict resolution complete: {}/{} auto-resolved, {} require manual input",
                autoResolved.size(), conflicts.size(), unresolved.size());
        return outcome;
    }

    /**
     * Builds the audit reason for a reviewed advisor proposal, distinguishing a
     * confirmation from an override. Override is decided by comparing the
     * <em>side</em> the reviewer chose against the side the proposal recommended
     * — not by entry equality, which would be ambiguous when both sides hold the
     * same value.
     */
    private static String reviewReason(UpdateConflict conflict, ResolutionProposal proposal,
                                       ManualResolution review) {
        boolean reviewChoseSource = review.getDecision() == ManualResolution.Decision.ACCEPT_SOURCE;
        boolean overridden = reviewChoseSource != proposalPrefersSource(conflict, proposal);
        String base = String.format("advisor[%s] confidence=%.3f: %s",
                proposal.source(), proposal.confidence(), proposal.rationale());
        if (!overridden) {
            return base;
        }
        return String.format("reviewer overrode advisor[%s] (proposal confidence=%.3f), kept '%s': %s",
                proposal.source(), proposal.confidence(), review.getChosenBranch(),
                review.getRationale() != null ? review.getRationale() : "(no rationale provided)");
    }

    /**
     * Determines which side a proposal recommends, giving reference identity
     * priority over value equality so the answer is unambiguous even when the
     * two sides carry equal values.
     */
    private static boolean proposalPrefersSource(UpdateConflict conflict, ResolutionProposal proposal) {
        SemanticChangeEntry chosen = proposal.chosenEntry();
        if (chosen == conflict.getSourceEntry()) {
            return true;
        }
        if (chosen == conflict.getTargetEntry()) {
            return false;
        }
        return chosen.equals(conflict.getSourceEntry());
    }
}
