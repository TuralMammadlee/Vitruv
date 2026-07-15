package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuditHistoryAdvisor} (RAG-over-history Beta-Bernoulli baseline).
 */
class AuditHistoryAdvisorTest {

    private static SemanticChangeEntry entry(ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ATTRIBUTE_CHANGED")
                .elementUuid("uuid-1")
                .eClass("entities::Entity")
                .feature("name")
                .origin(origin)
                .build();
    }

    private static UpdateConflict conflict() {
        return new UpdateConflict("uuid-1", "entities::Entity", "name",
                "source", "target", entry(ChangeOrigin.ORIGINAL), entry(ChangeOrigin.ORIGINAL));
    }

    @Test
    @DisplayName("stays silent during cold start (below minObservations)")
    void coldStartReturnsEmpty() {
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();
        UpdateConflict c = conflict();
        history.recordSourceWin(c);  // only 1 observation, default min is 3

        Optional<ResolutionProposal> proposal = new AuditHistoryAdvisor(history).propose(c);
        assertTrue(proposal.isEmpty());
    }

    @Test
    @DisplayName("proposes the historically dominant side once enough evidence exists")
    void proposesDominantSide() {
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();
        UpdateConflict c = conflict();
        history.recordSourceWin(c).recordSourceWin(c).recordSourceWin(c).recordSourceWin(c);

        Optional<ResolutionProposal> proposal = new AuditHistoryAdvisor(history).propose(c);
        assertTrue(proposal.isPresent());
        assertSame(c.getSourceEntry(), proposal.get().chosenEntry());
        // posterior p(source) = (4+1)/(4+1+1) = 0.833...
        assertEquals(0.833, proposal.get().confidence(), 0.01);
    }

    @Test
    @DisplayName("confidence is higher for one-sided history than for split history")
    void confidenceReflectsAgreement() {
        UpdateConflict c = conflict();

        InMemoryResolutionHistory oneSided = new InMemoryResolutionHistory();
        for (int i = 0; i < 10; i++) oneSided.recordTargetWin(c);

        InMemoryResolutionHistory split = new InMemoryResolutionHistory();
        for (int i = 0; i < 5; i++) { split.recordSourceWin(c); split.recordTargetWin(c); }

        double oneSidedConf = new AuditHistoryAdvisor(oneSided).propose(c).orElseThrow().confidence();
        double splitConf = new AuditHistoryAdvisor(split).propose(c).orElseThrow().confidence();

        assertTrue(oneSidedConf > splitConf,
                "one-sided history must yield higher confidence than an even split");
        assertSame(c.getTargetEntry(), new AuditHistoryAdvisor(oneSided).propose(c).orElseThrow().chosenEntry());
    }

    @Test
    @DisplayName("history is keyed by signature, not branch names")
    void differentSignatureHasNoEvidence() {
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();
        UpdateConflict trained = conflict();
        history.recordSourceWin(trained).recordSourceWin(trained).recordSourceWin(trained);

        // Different feature → different signature → no evidence
        UpdateConflict other = new UpdateConflict("uuid-1", "entities::Entity", "other-feature",
                "source", "target", entry(ChangeOrigin.ORIGINAL), entry(ChangeOrigin.ORIGINAL));

        assertFalse(new AuditHistoryAdvisor(history).propose(other).isPresent());
    }
}
