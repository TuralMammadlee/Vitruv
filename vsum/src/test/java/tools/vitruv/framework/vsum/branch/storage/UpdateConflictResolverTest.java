package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.ConflictResolutionStrategy;
import tools.vitruv.framework.vsum.branch.DomainValidator;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.ManualResolution;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UpdateConflictResolver}.
 *
 * <p>Each nested class covers one tier of the resolution strategy so
 * that failures point directly to the relevant tier.
 */
class UpdateConflictResolverTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SemanticChangeEntry entry(String uuid, String feature,
                                              SemanticChangeType type,
                                              ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType(type.name())
                .elementUuid(uuid)
                .eClass("entities::Entity")
                .feature(feature)
                .origin(origin)
                .build();
    }

    private static UpdateConflict conflict(SemanticChangeEntry src, SemanticChangeEntry tgt) {
        return new UpdateConflict(
                src.getElementUuid(), src.getEClass(), src.getFeature(),
                "source-branch", "target-branch",
                src, tgt);
    }

    // ── Empty input ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns empty outcome when conflict list is empty")
    void emptyListReturnsEmptyOutcome() {
        UpdateConflictResolver resolver = new UpdateConflictResolver(DomainValidator.NONE);
        AutoResolutionOutcome outcome = resolver.resolve(List.of());

        assertTrue(outcome.getAutoResolved().isEmpty());
        assertTrue(outcome.getUnresolved().isEmpty());
        assertTrue(outcome.isFullyResolved());
        assertEquals(0, outcome.totalCount());
    }

    // ── Tier 1: Origin rule ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 1 — origin rule")
    class OriginRule {

        @Test
        @DisplayName("O_C conflict: ORIGINAL source wins, no UI needed")
        void originalSourceBeatsConsequentialTarget() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_SET, ChangeOrigin.CONSEQUENTIAL);
            UpdateConflict c = conflict(src, tgt);

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE).resolve(List.of(c));

            assertEquals(1, outcome.getAutoResolved().size());
            assertTrue(outcome.getUnresolved().isEmpty());
            assertTrue(outcome.isFullyResolved());

            AutoResolutionOutcome.ResolvedConflict rc = outcome.getAutoResolved().get(0);
            assertSame(src, rc.chosenEntry(), "source (ORIGINAL) entry must be chosen");
            assertTrue(rc.reason().contains("ORIGINAL"));
        }

        @Test
        @DisplayName("C_O conflict: ORIGINAL target wins, no UI needed")
        void originalTargetBeatsConsequentialSource() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_SET, ChangeOrigin.ORIGINAL);
            UpdateConflict c = conflict(src, tgt);

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE).resolve(List.of(c));

            assertEquals(1, outcome.getAutoResolved().size());
            assertTrue(outcome.isFullyResolved());

            AutoResolutionOutcome.ResolvedConflict rc = outcome.getAutoResolved().get(0);
            assertSame(tgt, rc.chosenEntry(), "target (ORIGINAL) entry must be chosen");
        }

        @Test
        @DisplayName("O_O conflict: origin rule does not fire")
        void sameOriginOriginalDoesNotAutoResolve() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            UpdateConflict c = conflict(src, tgt);

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE).resolve(List.of(c));

            // With NONE validator, O_O falls through to manual
            assertTrue(outcome.getAutoResolved().isEmpty());
            assertEquals(1, outcome.getUnresolved().size());
            assertFalse(outcome.isFullyResolved());
        }

        @Test
        @DisplayName("C_C conflict: origin rule does not fire")
        void bothConsequentialDoesNotAutoResolve() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL);
            UpdateConflict c = conflict(src, tgt);

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE).resolve(List.of(c));

            assertTrue(outcome.getAutoResolved().isEmpty());
            assertEquals(1, outcome.getUnresolved().size());
        }

        @Test
        @DisplayName("UNKNOWN_UNKNOWN: neither tier fires, goes to manual")
        void unknownOriginGoesToManual() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.UNKNOWN);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.UNKNOWN);
            UpdateConflict c = conflict(src, tgt);

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE).resolve(List.of(c));

            assertTrue(outcome.getAutoResolved().isEmpty());
            assertEquals(1, outcome.getUnresolved().size());
        }
    }

    // ── Tier 2: Domain validator ──────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 2 — domain validator")
    class DomainValidatorTier {

        @Test
        @DisplayName("validator suggestion is applied for O_O conflict")
        void validatorResolvesOOConflict() {
            SemanticChangeEntry src = entry("uuid-1", "version",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgt = entry("uuid-1", "version",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            UpdateConflict c = conflict(src, tgt);

            // Domain rule: always prefer source branch for "version" feature
            DomainValidator validator = conflict2 ->
                    "version".equals(conflict2.getFeatureName())
                            ? Optional.of(conflict2.getSourceEntry())
                            : Optional.empty();

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(validator).resolve(List.of(c));

            assertEquals(1, outcome.getAutoResolved().size());
            assertTrue(outcome.isFullyResolved());
            assertSame(src, outcome.getAutoResolved().get(0).chosenEntry());
            assertTrue(outcome.getAutoResolved().get(0).reason().contains("domain-validator"));
        }

        @Test
        @DisplayName("validator suggestion is applied for C_C conflict")
        void validatorResolvesCCConflict() {
            SemanticChangeEntry src = entry("uuid-1", "status",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL);
            SemanticChangeEntry tgt = entry("uuid-1", "status",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL);
            UpdateConflict c = conflict(src, tgt);

            DomainValidator validator = conflict2 ->
                    Optional.of(conflict2.getTargetEntry());

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(validator).resolve(List.of(c));

            assertEquals(1, outcome.getAutoResolved().size());
            assertSame(tgt, outcome.getAutoResolved().get(0).chosenEntry());
        }

        @Test
        @DisplayName("validator returning empty falls through to manual")
        void validatorEmptyFallsToManual() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            UpdateConflict c = conflict(src, tgt);

            // Validator never matches
            DomainValidator validator = conflict2 -> Optional.empty();

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(validator).resolve(List.of(c));

            assertTrue(outcome.getAutoResolved().isEmpty());
            assertEquals(1, outcome.getUnresolved().size());
        }

        @Test
        @DisplayName("validator is NOT consulted for mixed-origin (tier 1 fires first)")
        void validatorSkippedForMixedOrigin() {
            SemanticChangeEntry src = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgt = entry("uuid-1", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL);
            UpdateConflict c = conflict(src, tgt);

            // Validator would prefer target — but it should never be called
            boolean[] validatorCalled = {false};
            DomainValidator validator = conflict2 -> {
                validatorCalled[0] = true;
                return Optional.of(conflict2.getTargetEntry());
            };

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(validator).resolve(List.of(c));

            assertFalse(validatorCalled[0], "domain validator must not be called for mixed-origin conflicts");
            // Tier 1 chose src (ORIGINAL)
            assertSame(src, outcome.getAutoResolved().get(0).chosenEntry());
        }
    }

    // ── Tier 3: Learned advisor ───────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 3 — learned advisor")
    class AdvisorTier {

        private UpdateConflict ooConflict(String feature) {
            SemanticChangeEntry src = entry("uuid-1", feature,
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgt = entry("uuid-1", feature,
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            return conflict(src, tgt);
        }

        @Test
        @DisplayName("confident proposal is auto-applied")
        void confidentProposalApplied() {
            UpdateConflict c = ooConflict("name");
            ConflictResolutionAdvisor advisor = conflict ->
                    Optional.of(new ResolutionProposal(conflict.getTargetEntry(), 0.95, "ml says target", "test"));

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE, advisor, 0.8).resolve(List.of(c));

            assertEquals(1, outcome.getAutoResolved().size());
            assertTrue(outcome.isFullyResolved());
            assertSame(c.getTargetEntry(), outcome.getAutoResolved().get(0).chosenEntry());
            assertTrue(outcome.getAutoResolved().get(0).reason().contains("advisor"));
        }

        @Test
        @DisplayName("low-confidence proposal falls through to manual and is attached as an advisory hint")
        void lowConfidenceProposalDeferred() {
            UpdateConflict c = ooConflict("name");
            ResolutionProposal proposal =
                    new ResolutionProposal(c.getSourceEntry(), 0.55, "unsure", "test");
            ConflictResolutionAdvisor advisor = conflict -> Optional.of(proposal);

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE, advisor, 0.8).resolve(List.of(c));

            assertTrue(outcome.getAutoResolved().isEmpty());
            assertEquals(1, outcome.getUnresolved().size());
            assertSame(proposal, outcome.getAdvisoryProposal(c).orElseThrow(),
                    "below-threshold proposal must travel to the manual tier as a hint");
        }

        @Test
        @DisplayName("confident proposal that the reviewer overrides keeps the other side")
        void reviewerOverridesConfidentProposal() {
            UpdateConflict c = ooConflict("name");
            ConflictResolutionAdvisor advisor = conflict ->
                    Optional.of(new ResolutionProposal(conflict.getSourceEntry(), 0.95, "ml says source", "test"));
            // Reviewer overrides the proposal and keeps the target side instead.
            ConflictResolutionStrategy reviewer = new ConflictResolutionStrategy() {
                @Override
                public ManualResolution resolve(UpdateConflict conflict) {
                    return ManualResolution.deferred(conflict, "unused");
                }
                @Override
                public ManualResolution reviewProposal(UpdateConflict conflict, ResolutionProposal proposal) {
                    return ManualResolution.acceptTarget(conflict, "reviewer prefers target");
                }
            };

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE, advisor, 0.8, reviewer).resolve(List.of(c));

            assertEquals(1, outcome.getAutoResolved().size());
            assertSame(c.getTargetEntry(), outcome.getAutoResolved().get(0).chosenEntry());
            assertTrue(outcome.getAutoResolved().get(0).reason().contains("overrode"));
        }

        @Test
        @DisplayName("confident proposal the reviewer defers routes to manual resolution")
        void reviewerDefersConfidentProposal() {
            UpdateConflict c = ooConflict("name");
            ConflictResolutionAdvisor advisor = conflict ->
                    Optional.of(new ResolutionProposal(conflict.getSourceEntry(), 0.95, "ml says source", "test"));
            // A lambda reviewer would inherit the default reviewProposal (auto-confirm),
            // so override it explicitly to defer.
            ConflictResolutionStrategy deferringReviewer = new ConflictResolutionStrategy() {
                @Override
                public ManualResolution resolve(UpdateConflict conflict) {
                    return ManualResolution.deferred(conflict, "unused");
                }
                @Override
                public ManualResolution reviewProposal(UpdateConflict conflict, ResolutionProposal proposal) {
                    return ManualResolution.deferred(conflict, "reviewer wants a human");
                }
            };

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(DomainValidator.NONE, advisor, 0.8, deferringReviewer)
                            .resolve(List.of(c));

            assertTrue(outcome.getAutoResolved().isEmpty());
            assertEquals(1, outcome.getUnresolved().size());
            assertSame(c, outcome.getUnresolved().get(0));
        }

        @Test
        @DisplayName("domain validator is consulted before the advisor")
        void domainValidatorWinsOverAdvisor() {
            UpdateConflict c = ooConflict("version");
            DomainValidator validator = conflict -> Optional.of(conflict.getSourceEntry());
            boolean[] advisorCalled = {false};
            ConflictResolutionAdvisor advisor = conflict -> {
                advisorCalled[0] = true;
                return Optional.of(new ResolutionProposal(conflict.getTargetEntry(), 0.99, "x", "test"));
            };

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(validator, advisor, 0.8).resolve(List.of(c));

            assertFalse(advisorCalled[0], "advisor must not be consulted when domain validator resolves");
            assertSame(c.getSourceEntry(), outcome.getAutoResolved().get(0).chosenEntry());
        }
    }

    // ── Mixed list ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed conflict list")
    class MixedList {

        @Test
        @DisplayName("correctly splits auto-resolved and unresolved across all tiers")
        void mixedListSplitsCorrectly() {
            // Conflict A: O_C → auto-resolved by origin rule
            SemanticChangeEntry srcA = entry("uuid-A", "name",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgtA = entry("uuid-A", "name",
                    SemanticChangeType.ATTRIBUTE_SET, ChangeOrigin.CONSEQUENTIAL);
            UpdateConflict cA = conflict(srcA, tgtA);

            // Conflict B: O_O on "version" → auto-resolved by domain validator
            SemanticChangeEntry srcB = entry("uuid-B", "version",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgtB = entry("uuid-B", "version",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            UpdateConflict cB = conflict(srcB, tgtB);

            // Conflict C: O_O on "description" → no suggestion → manual
            SemanticChangeEntry srcC = entry("uuid-C", "description",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            SemanticChangeEntry tgtC = entry("uuid-C", "description",
                    SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL);
            UpdateConflict cC = conflict(srcC, tgtC);

            DomainValidator validator = c ->
                    "version".equals(c.getFeatureName())
                            ? Optional.of(c.getSourceEntry())
                            : Optional.empty();

            AutoResolutionOutcome outcome =
                    new UpdateConflictResolver(validator).resolve(List.of(cA, cB, cC));

            assertEquals(3, outcome.totalCount());
            assertEquals(2, outcome.getAutoResolved().size());
            assertEquals(1, outcome.getUnresolved().size());
            assertFalse(outcome.isFullyResolved());
            assertTrue(outcome.hasUnresolved());
            assertSame(cC, outcome.getUnresolved().get(0));
        }
    }
}
