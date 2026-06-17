package tools.vitruv.framework.vsum.branch.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.storage.AuditLogger;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictAnalyzer;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.FileChangeInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the conflict resolution pipeline.
 *
 * <p>Walks the full chain of components — analyzer → resolver → audit logger —
 * in memory (no Git, no real model). The aim is to catch wiring regressions
 * between collaborators that unit tests on individual classes cannot.
 *
 * <p><b>Scope:</b> deliberately narrow. Verifies the chain runs end-to-end and
 * produces an audit file in the expected location with the expected shape.
 * Integration-level Git scenarios live in
 * {@link MergeAuditPipelineIntegrationTest}.
 */
@Tag("smoke")
class ConflictResolutionPipelineSmokeTest {

    @Test
    @DisplayName("analyzer → resolver → audit produces a written audit file in headless mode")
    void fullPipelineHeadless(@TempDir Path repoRoot) throws IOException {
        // Arrange: simulate two branches where source deletes an element that target updates
        ChangelogDocument sourceChangelog = changelogOf(
                entry(SemanticChangeType.ELEMENT_DELETED, "elt-1", null, ChangeOrigin.ORIGINAL));
        ChangelogDocument targetChangelog = changelogOf(
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "elt-1", null, ChangeOrigin.ORIGINAL),
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "elt-1", null, ChangeOrigin.ORIGINAL));

        // Act 1: detect
        List<DeletionConflict> conflicts = new DeletionConflictAnalyzer()
                .analyze("feature", "main", sourceChangelog, targetChangelog, /*ancestorAvailable*/ true);
        assertEquals(1, conflicts.size(), "exactly one deletion conflict expected");

        // Act 2: resolve (headless — no console → applies policy default)
        MergePolicy policy = new MergePolicy(DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.methodologist());
        List<DeletionConflictResolver.Resolution> resolutions =
                new DeletionConflictResolver(policy).resolve(conflicts);
        assertEquals(1, resolutions.size());
        assertEquals(DeletionPolicy.RESTRICT_DELETIONS, resolutions.get(0).getChosenPolicy(),
                "headless path must apply the merge policy's default deletion policy");

        // Act 3: persist to audit log
        AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");
        for (DeletionConflictResolver.Resolution r : resolutions) {
            logger.log(AuditLogEntry.forDeletion(r, "feature", "main"));
        }
        Path written = logger.flush();

        // Assert
        assertTrue(Files.exists(written), "audit file must exist after flush returns");
        String json = Files.readString(written);
        assertAll("audit JSON contents",
                () -> assertTrue(json.contains("\"DELETION\"")),
                () -> assertTrue(json.contains("\"elementUuid\": \"elt-1\"")),
                () -> assertTrue(json.contains("RESTRICT_DELETIONS"),
                        "the chosen policy must appear under resolution"),
                () -> assertTrue(json.contains("\"sourceBranch\": \"feature\"")),
                () -> assertTrue(json.contains("\"targetBranch\": \"main\"")));
    }

    @Test
    @DisplayName("empty changelogs short-circuit: no conflicts, no audit entries, no file write needed")
    void emptyChangelogsShortCircuit(@TempDir Path repoRoot) throws IOException {
        ChangelogDocument empty = changelogOf();

        List<DeletionConflict> conflicts = new DeletionConflictAnalyzer()
                .analyze("feature", "main", empty, empty, true);
        assertTrue(conflicts.isEmpty());

        AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");
        // No resolution → no log entries; flush still produces an empty array file
        Path written = logger.flush();
        assertEquals("[]", Files.readString(written).trim());
    }

    @Test
    @DisplayName("RESTRICT default policy is honoured for multiple conflicts")
    void restrictPolicyAppliedToMany(@TempDir Path repoRoot) throws IOException {
        // Two distinct deletions, two updates — analyzer produces two conflicts
        ChangelogDocument source = changelogOf(
                entry(SemanticChangeType.ELEMENT_DELETED, "elt-A", null, ChangeOrigin.ORIGINAL),
                entry(SemanticChangeType.ELEMENT_DELETED, "elt-B", null, ChangeOrigin.CONSEQUENTIAL));
        ChangelogDocument target = changelogOf(
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "elt-A", null, ChangeOrigin.ORIGINAL),
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "elt-B", null, ChangeOrigin.ORIGINAL));

        List<DeletionConflict> conflicts = new DeletionConflictAnalyzer()
                .analyze("feature", "main", source, target, true);
        assertEquals(2, conflicts.size());

        MergePolicy policy = new MergePolicy(DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.methodologist());
        List<DeletionConflictResolver.Resolution> resolutions =
                new DeletionConflictResolver(policy).resolve(conflicts);

        assertEquals(2, resolutions.size());
        for (DeletionConflictResolver.Resolution r : resolutions) {
            assertEquals(DeletionPolicy.RESTRICT_DELETIONS, r.getChosenPolicy());
            assertNull(r.getRationale(), "headless resolutions carry no human rationale");
        }

        AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");
        for (DeletionConflictResolver.Resolution r : resolutions) {
            logger.log(AuditLogEntry.forDeletion(r, "feature", "main"));
        }
        Path written = logger.flush();
        String json = Files.readString(written);

        assertTrue(json.contains("elt-A"));
        assertTrue(json.contains("elt-B"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SemanticChangeEntry entry(SemanticChangeType type, String elementUuid,
                                              String containerUuid, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType(type.name())
                .elementUuid(elementUuid)
                .eClass("entities::Entity")
                .feature(type == SemanticChangeType.ATTRIBUTE_CHANGED ? "name" : null)
                .containerUuid(containerUuid)
                .origin(origin)
                .build();
    }

    private static ChangelogDocument changelogOf(SemanticChangeEntry... entries) {
        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = "1.1";
        FileChangeInfo fc = new FileChangeInfo();
        fc.operation = "MODIFIED";
        fc.path = "model/test.xmi";
        fc.semanticChanges = new ArrayList<>(List.of(entries));
        doc.fileChanges = new ArrayList<>(List.of(fc));
        return doc;
    }
}
