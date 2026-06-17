package tools.vitruv.framework.vsum.branch.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.MergeManager;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.ModelMergeResult;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.CommitInfo;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.FileChangeInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.commitFile;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.initRepo;

/**
 * End-to-end integration test for the merge → conflict resolution → audit pipeline.
 *
 * <p>Uses a real Git repository (via {@link tools.vitruv.framework.vsum.branch.GitTestHelper})
 * to verify behaviours that span multiple components:
 * <ul>
 *   <li>{@link MergeManager#merge} detects a CONFLICTING merge and runs the
 *       semantic analyzer on the on-disk changelogs of both branches.</li>
 *   <li>{@link MergeManager#resolveDeletionConflicts} routes through the
 *       headless resolver (no console available in the test JVM), honours the
 *       {@link MergePolicy}'s default deletion policy, and persists every
 *       decision to the audit log under {@code .vitruvius/audit/}.</li>
 *   <li>The audit file is written synchronously — it exists on disk before
 *       any subsequent JGit checkout could undo work.</li>
 * </ul>
 *
 * <p>These behaviours are not covered by unit tests on individual components.
 * If any wiring between MergeManager, the analyzers, the resolver, and the
 * audit logger regresses, this test fails.
 */
@Tag("integration")
class MergeAuditPipelineIntegrationTest {

    @Test
    @DisplayName("conflicting merge detects deletion conflict and resolveDeletionConflicts persists audit log")
    void conflictingMergeProducesAuditLog(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepo(repoDir)) {
            // Set up two diverging branches that touch the same model file
            commitFile(git, repoDir, "system.model", "<System v='base'/>", "Base version");

            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            String featureSha = commitFile(git, repoDir, "system.model",
                    "<System v='feature' />", "Feature change");

            git.checkout().setName("master").call();
            String masterSha = commitFile(git, repoDir, "system.model",
                    "<System v='master' />", "Master change");

            // Write changelogs to disk so the semantic analyzer has something to read.
            // Schema: feature DELETED element-1, master UPDATED an attribute on element-1.
            writeChangelog(repoDir, "feature", featureSha,
                    entry(SemanticChangeType.ELEMENT_DELETED, "element-1", null, null, ChangeOrigin.ORIGINAL));
            writeChangelog(repoDir, "master", masterSha,
                    entry(SemanticChangeType.ATTRIBUTE_CHANGED, "element-1", null, "name", ChangeOrigin.ORIGINAL));

            // Act 1 — perform the merge (will conflict on system.model)
            MergeManager manager = new MergeManager(repoDir);
            ModelMergeResult result = manager.merge("feature");

            assertEquals(ModelMergeResult.MergeStatus.CONFLICTING, result.getStatus(),
                    "system.model differs on both branches → CONFLICTING");
            assertEquals(1, manager.getLastDeletionConflicts().size(),
                    "the analyzer must pick up the changelogs and detect the deletion conflict");

            // Act 2 — resolve in headless mode (no console in test JVM).
            // Policy default is RESTRICT_DELETIONS so we avoid recovery side-effects in this test.
            MergePolicy policy = new MergePolicy(DeletionPolicy.RESTRICT_DELETIONS,
                    RoleDefinition.methodologist());
            List<DeletionConflictResolver.Resolution> resolutions =
                    manager.resolveDeletionConflicts(policy, "feature");

            assertEquals(1, resolutions.size());
            assertEquals(DeletionPolicy.RESTRICT_DELETIONS, resolutions.get(0).getChosenPolicy());

            // Assert — audit file exists and contains expected fields
            Path auditDir = repoDir.resolve(".vitruvius").resolve("audit");
            try (Stream<Path> files = Files.list(auditDir)) {
                List<Path> audits = files.filter(p -> p.getFileName().toString().endsWith(".audit.json")).toList();
                assertEquals(1, audits.size(), "exactly one audit file should be written per merge session");

                String json = Files.readString(audits.get(0));
                assertAll(
                        () -> assertTrue(json.contains("\"DELETION\"")),
                        () -> assertTrue(json.contains("element-1")),
                        () -> assertTrue(json.contains("RESTRICT_DELETIONS")),
                        () -> assertTrue(json.contains("\"sourceBranch\": \"feature\"")),
                        () -> assertTrue(json.contains("\"targetBranch\": \"master\"")));
            }
        }
    }

    @Test
    @DisplayName("RECOVER_FROM_ANCESTOR policy triggers a single ancestor checkout (not one per conflict)")
    void recoverFromAncestorPolicyExecutes(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepo(repoDir)) {
            commitFile(git, repoDir, "system.model", "<System v='base'/>", "Base version");

            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            String featureSha = commitFile(git, repoDir, "system.model",
                    "<System v='feature' />", "Feature change");

            git.checkout().setName("master").call();
            String masterSha = commitFile(git, repoDir, "system.model",
                    "<System v='master' />", "Master change");

            // TWO deletion conflicts on two different elements — fix #4 must produce
            // ONE ancestor checkout for both, not two.
            writeChangelog(repoDir, "feature", featureSha,
                    entry(SemanticChangeType.ELEMENT_DELETED, "element-A", null, null, ChangeOrigin.ORIGINAL),
                    entry(SemanticChangeType.ELEMENT_DELETED, "element-B", null, null, ChangeOrigin.ORIGINAL));
            writeChangelog(repoDir, "master", masterSha,
                    entry(SemanticChangeType.ATTRIBUTE_CHANGED, "element-A", null, "name", ChangeOrigin.ORIGINAL),
                    entry(SemanticChangeType.ATTRIBUTE_CHANGED, "element-B", null, "label", ChangeOrigin.ORIGINAL));

            MergeManager manager = new MergeManager(repoDir);
            manager.merge("feature");
            assertEquals(2, manager.getLastDeletionConflicts().size(),
                    "both deletion conflicts must be detected");

            MergePolicy policy = new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR,
                    RoleDefinition.methodologist());
            List<DeletionConflictResolver.Resolution> resolutions =
                    manager.resolveDeletionConflicts(policy, "feature");

            assertEquals(2, resolutions.size());
            assertTrue(resolutions.stream().allMatch(
                    r -> r.getChosenPolicy() == DeletionPolicy.RECOVER_FROM_ANCESTOR),
                    "every resolution must use the policy default in headless mode");

            // Ancestor's .vitruvius/vsum content (set up by GitTestHelper.initRepo) must
            // still be intact in the working tree after recovery.
            Path uuidFile = repoDir.resolve(".vitruvius").resolve("vsum").resolve("master").resolve("uuid.uuid");
            assertTrue(Files.exists(uuidFile),
                    "ancestor vsum file must remain present after RECOVER_FROM_ANCESTOR");
            assertEquals("fake-uuid-content", Files.readString(uuidFile),
                    "ancestor file content must be restored to its ancestor state");
        }
    }

    @Test
    @DisplayName("deletion + update decisions from one merge land in a single audit file (no per-pass collision)")
    void deletionAndUpdateShareOneAuditFile(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepo(repoDir)) {
            commitFile(git, repoDir, "system.model", "<System v='base'/>", "Base version");

            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            String featureSha = commitFile(git, repoDir, "system.model",
                    "<System v='feature' />", "Feature change");

            git.checkout().setName("master").call();
            String masterSha = commitFile(git, repoDir, "system.model",
                    "<System v='master' />", "Master change");

            // element-1: feature deletes it, master updates it → DELETION conflict.
            // element-2: both branches update the same feature with mixed origin → UPDATE
            //            conflict that auto-resolves (ORIGINAL over CONSEQUENTIAL).
            writeChangelog(repoDir, "feature", featureSha,
                    entry(SemanticChangeType.ELEMENT_DELETED, "element-1", null, null, ChangeOrigin.ORIGINAL),
                    entry(SemanticChangeType.ATTRIBUTE_CHANGED, "element-2", null, "name", ChangeOrigin.ORIGINAL));
            writeChangelog(repoDir, "master", masterSha,
                    entry(SemanticChangeType.ATTRIBUTE_CHANGED, "element-1", null, "name", ChangeOrigin.ORIGINAL),
                    entry(SemanticChangeType.ATTRIBUTE_CHANGED, "element-2", null, "name", ChangeOrigin.CONSEQUENTIAL));

            MergeManager manager = new MergeManager(repoDir);
            manager.merge("feature");
            assertEquals(1, manager.getLastDeletionConflicts().size(), "one deletion conflict expected");
            assertEquals(1, manager.getLastUpdateConflicts().size(), "one update conflict expected");

            // resolveAllConflicts runs the deletion pass and the update pass back-to-back.
            // Both must write into the SAME session audit file — the bug being guarded is the
            // second pass overwriting the first when each pass opened its own timestamp-named file.
            manager.resolveAllConflicts(
                    new MergePolicy(DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.methodologist()),
                    "feature");

            Path auditDir = repoDir.resolve(".vitruvius").resolve("audit");
            try (Stream<Path> files = Files.list(auditDir)) {
                List<Path> audits = files.filter(p -> p.getFileName().toString().endsWith(".audit.json")).toList();
                assertEquals(1, audits.size(),
                        "exactly one audit file per merge session, even with both deletion and update decisions");

                String json = Files.readString(audits.get(0));
                assertAll("the single file holds both decision kinds",
                        () -> assertTrue(json.contains("\"DELETION\""), "deletion decision must survive"),
                        () -> assertTrue(json.contains("element-1"), "deleted element must be recorded"),
                        () -> assertTrue(json.contains("\"UPDATE_AUTO_RESOLVED\""), "update decision must be present"),
                        () -> assertTrue(json.contains("element-2"), "auto-resolved element must be recorded"));
            }
        }
    }

    @Test
    @DisplayName("merge with no semantic conflicts produces no audit file")
    void noSemanticConflictsNoAudit(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepo(repoDir)) {
            // Two branches that touch different files — no merge conflict at all
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            commitFile(git, repoDir, "component.model", "<Component/>", "Add component");
            git.checkout().setName("master").call();

            MergeManager manager = new MergeManager(repoDir);
            ModelMergeResult result = manager.merge("feature");

            assertTrue(result.isSuccessful(), "expected fast-forward or merged");
            assertTrue(manager.getLastDeletionConflicts().isEmpty());

            // resolveDeletionConflicts must short-circuit
            List<DeletionConflictResolver.Resolution> resolutions = manager.resolveDeletionConflicts(
                    new MergePolicy(DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.methodologist()),
                    "feature");
            assertTrue(resolutions.isEmpty(), "no conflicts → no resolutions");

            Path auditDir = repoDir.resolve(".vitruvius").resolve("audit");
            assertFalse(Files.exists(auditDir),
                    "audit directory should not be created when no resolutions were made");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void writeChangelog(Path repoDir, String branch, String fullSha,
                                        SemanticChangeEntry... entries) throws IOException {
        String shortSha = fullSha.substring(0, 7);
        Path jsonDir = repoDir.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
        Files.createDirectories(jsonDir);

        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = "1.1";
        doc.commit = new CommitInfo();
        doc.commit.sha = fullSha;
        doc.commit.shortSha = shortSha;
        doc.commit.branch = branch;

        FileChangeInfo fc = new FileChangeInfo();
        fc.operation = "MODIFIED";
        fc.path = "system.model";
        fc.semanticChanges = new ArrayList<>(List.of(entries));
        doc.fileChanges = new ArrayList<>(List.of(fc));

        Gson gson = new GsonBuilder().setPrettyPrinting()
                .registerTypeAdapter(ChangeOrigin.class,
                        (JsonSerializer<ChangeOrigin>) (src, t, c) -> new JsonPrimitive(src.name()))
                .create();
        Files.writeString(jsonDir.resolve(shortSha + ".json"), gson.toJson(doc));
    }

    private static SemanticChangeEntry entry(SemanticChangeType type, String elementUuid,
                                              String containerUuid, String feature, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType(type.name())
                .elementUuid(elementUuid)
                .eClass("entities::Entity")
                .feature(feature)
                .containerUuid(containerUuid)
                .origin(origin)
                .build();
    }
}
