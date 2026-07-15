package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.OwnerEscalation;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OwnerEscalationStoreTest {

    @Test
    @DisplayName("save and load round-trip an AWAITING_OWNER escalation")
    void saveAndLoadAwaiting(@TempDir Path repoRoot) throws IOException {
        OwnerEscalationStore store = new OwnerEscalationStore(repoRoot);
        OwnerEscalation escalation = OwnerEscalation.awaitingOwner(
                "element-1", "feature", "main",
                List.of("owner@example.com"), "2026-01-01T00:00:00Z");

        Path written = store.save(escalation);
        assertTrue(Files.exists(written));

        Optional<OwnerEscalation> loaded = store.load("element-1", "feature", "main");
        assertTrue(loaded.isPresent());
        assertTrue(loaded.get().isAwaitingOwner());
        assertEquals(List.of("owner@example.com"), loaded.get().getAssignees());
    }

    @Test
    @DisplayName("save and load ESCALATED_TO_SENIOR escalation")
    void saveAndLoadSenior(@TempDir Path repoRoot) throws IOException {
        OwnerEscalationStore store = new OwnerEscalationStore(repoRoot);
        store.save(OwnerEscalation.escalatedToSenior(
                "element-2", "feature", "main", "Owner denied",
                List.of("methodologist@example.com")));

        Optional<OwnerEscalation> loaded = store.load("element-2", "feature", "main");
        assertTrue(loaded.isPresent());
        assertTrue(loaded.get().isEscalatedToSenior());
        assertEquals("Owner denied", loaded.get().getReason());
    }
}
