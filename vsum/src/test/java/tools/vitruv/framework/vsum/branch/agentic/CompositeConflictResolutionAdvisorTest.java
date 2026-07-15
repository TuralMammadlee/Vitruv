package tools.vitruv.framework.vsum.branch.agentic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CompositeConflictResolutionAdvisor} chaining semantics.
 */
class CompositeConflictResolutionAdvisorTest {

    private static UpdateConflict conflict() {
        SemanticChangeEntry entry = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED).emfType("ReplaceSingleValued")
                .elementUuid("uuid-1").eClass("entities::Entity").feature("name")
                .origin(ChangeOrigin.ORIGINAL).build();
        return new UpdateConflict("uuid-1", "entities::Entity", "name", "s", "t", entry, entry);
    }

    private static ResolutionProposal proposal(UpdateConflict c, String source) {
        return new ResolutionProposal(c.getSourceEntry(), 0.9, "r", source);
    }

    @Test
    @DisplayName("returns the first delegate's proposal and short-circuits the rest")
    void firstWins() {
        UpdateConflict c = conflict();
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        ConflictResolutionAdvisor first = conflict -> Optional.of(proposal(conflict, "first"));
        ConflictResolutionAdvisor second = conflict -> {
            secondCalled.set(true);
            return Optional.of(proposal(conflict, "second"));
        };

        Optional<ResolutionProposal> result =
                new CompositeConflictResolutionAdvisor(List.of(first, second)).propose(c);

        assertTrue(result.isPresent());
        assertEquals("first", result.get().source());
        assertFalse(secondCalled.get(), "second delegate must not be consulted once the first proposes");
    }

    @Test
    @DisplayName("falls through to the next delegate when the first abstains")
    void fallsThrough() {
        UpdateConflict c = conflict();
        ConflictResolutionAdvisor baseline = conflict -> Optional.empty();
        ConflictResolutionAdvisor agentic = conflict -> Optional.of(proposal(conflict, "agentic"));

        Optional<ResolutionProposal> result =
                CompositeConflictResolutionAdvisor.baselineThenAgentic(baseline, agentic).propose(c);

        assertTrue(result.isPresent());
        assertEquals("agentic", result.get().source());
        assertSame(c.getSourceEntry(), result.get().chosenEntry());
    }

    @Test
    @DisplayName("returns empty when every delegate abstains")
    void allAbstain() {
        Optional<ResolutionProposal> result = new CompositeConflictResolutionAdvisor(
                List.of(ConflictResolutionAdvisor.NONE, ConflictResolutionAdvisor.NONE)).propose(conflict());
        assertTrue(result.isEmpty());
    }
}
