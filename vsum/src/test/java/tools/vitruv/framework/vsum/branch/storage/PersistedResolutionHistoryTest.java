package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.ManualResolution;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link PersistedResolutionHistory} reconstructs resolution
 * outcomes purely from the audit files that {@link AuditLogger} writes — i.e.
 * that the learned-advisor evidence feeds itself from the persisted trail with
 * no manual bookkeeping.
 */
class PersistedResolutionHistoryTest {

    private static SemanticChangeEntry entry(ChangeOrigin origin, String to) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValued")
                .elementUuid("uuid-1")
                .eClass("entities::Entity")
                .feature("name")
                .from("old")
                .to(to)
                .origin(origin)
                .build();
    }

    /** An O_O conflict, so the origin rule does not pre-empt a recorded decision. */
    private static UpdateConflict conflict() {
        return new UpdateConflict("uuid-1", "entities::Entity", "name", "featureBranch", "main",
                entry(ChangeOrigin.ORIGINAL, "srcValue"), entry(ChangeOrigin.ORIGINAL, "tgtValue"));
    }

    @Test
    @DisplayName("empty audit directory yields no observations")
    void emptyHistory(@TempDir Path repo) {
        PersistedResolutionHistory history = new PersistedResolutionHistory(repo);
        assertEquals(0, history.outcomesFor(conflict()).total());
        assertTrue(history.decisionsFor(conflict(), 5).isEmpty());
    }

    @Test
    @DisplayName("aggregates source/target wins from persisted audit files by signature")
    void aggregatesFromDisk(@TempDir Path repo) throws IOException {
        UpdateConflict c = conflict();

        // Two source wins and one target win for the same signature, written by
        // the real AuditLogger exactly as a live merge would.
        AuditLogger logger = new AuditLogger(repo, "featureBranch", "main");
        logger.log(AuditLogEntry.forUpdateManual(
                ManualResolution.acceptSource(c, "keep source A"), "featureBranch", "main"));
        logger.log(AuditLogEntry.forUpdateManual(
                ManualResolution.acceptTarget(c, "keep target"), "featureBranch", "main"));
        logger.log(AuditLogEntry.forUpdateAutoResolved(
                new AutoResolutionOutcome.ResolvedConflict(c, c.getSourceEntry(), "advisor kept source"),
                "featureBranch", "main"));
        logger.flush();

        PersistedResolutionHistory history = new PersistedResolutionHistory(repo);
        ResolutionHistory.Outcomes outcomes = history.outcomesFor(c);

        assertEquals(2, outcomes.sourceWins());
        assertEquals(1, outcomes.targetWins());
        assertEquals(3, outcomes.total());

        List<ResolutionHistory.RecordedDecision> decisions = history.decisionsFor(c, 5);
        assertEquals(3, decisions.size());
        assertTrue(decisions.stream().anyMatch(d ->
                AuditLogEntry.SIDE_SOURCE.equals(d.chosenSide()) && "keep source A".equals(d.rationale())));
    }

    @Test
    @DisplayName("ignores deletion entries, which carry no update signature")
    void ignoresNonUpdateEntries(@TempDir Path repo) throws IOException {
        UpdateConflict c = conflict();
        AuditLogger logger = new AuditLogger(repo, "featureBranch", "main");
        logger.log(AuditLogEntry.forUpdateManual(
                ManualResolution.acceptSource(c, "keep source"), "featureBranch", "main"));
        logger.flush();

        // A different signature must not contribute to this conflict's tally.
        UpdateConflict other = new UpdateConflict("uuid-9", "entities::Other", "label",
                "featureBranch", "main",
                entry(ChangeOrigin.ORIGINAL, "a"), entry(ChangeOrigin.ORIGINAL, "b"));

        PersistedResolutionHistory history = new PersistedResolutionHistory(repo);
        assertEquals(1, history.outcomesFor(c).total());
        assertEquals(0, history.outcomesFor(other).total());
    }
}
