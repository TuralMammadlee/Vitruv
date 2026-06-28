package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.OwnerDecision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OwnerDecisionStoreTest {

    @Test
    @DisplayName("save and load round-trip an APPROVE decision")
    void saveAndLoadApprove(@TempDir Path repoRoot) throws IOException {
        OwnerDecisionStore store = new OwnerDecisionStore(repoRoot);
        OwnerDecision decision = OwnerDecision.of(
                "element-42", "owner@example.com", true,
                "Looks safe to recover", "feature", "main");

        Path written = store.save(decision);
        assertTrue(Files.exists(written));
        assertTrue(written.startsWith(repoRoot.resolve(".vitruvius").resolve("owner-decisions")));

        Optional<OwnerDecision> loaded = store.load("element-42", "feature", "main");
        assertTrue(loaded.isPresent());
        assertEquals(OwnerDecision.APPROVE, loaded.get().getDecision());
        assertTrue(loaded.get().isApproved());
        assertEquals("owner@example.com", loaded.get().getOwnerId());
        assertEquals("Looks safe to recover", loaded.get().getRationale());
    }

    @Test
    @DisplayName("load returns empty when no decision exists")
    void loadMissing(@TempDir Path repoRoot) throws IOException {
        OwnerDecisionStore store = new OwnerDecisionStore(repoRoot);
        assertTrue(store.load("missing-uuid", "feature", "main").isEmpty());
    }

    @Test
    @DisplayName("save and load a DENY decision")
    void saveAndLoadDeny(@TempDir Path repoRoot) throws IOException {
        OwnerDecisionStore store = new OwnerDecisionStore(repoRoot);
        store.save(OwnerDecision.of("element-99", "owner@example.com", false,
                "Too risky", "feature", "main"));

        Optional<OwnerDecision> loaded = store.load("element-99", "feature", "main");
        assertTrue(loaded.isPresent());
        assertEquals(OwnerDecision.DENY, loaded.get().getDecision());
        assertFalse(loaded.get().isApproved());
    }
}
