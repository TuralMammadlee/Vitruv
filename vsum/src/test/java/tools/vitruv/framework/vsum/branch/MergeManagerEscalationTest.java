package tools.vitruv.framework.vsum.branch;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.OwnerEscalation;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.storage.AuditLogger;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.initRepo;

/**
 * Tests the clearance-denied escalation path on {@link MergeManager}:
 * notify owner → build review → check owner decision → MERGE BLOCKED or owner-approved.
 */
class MergeManagerEscalationTest {

    @Nested
    @DisplayName("escalation flow")
    class EscalationFlow {

        @Test
        @DisplayName("clearance-denied conflict writes notification and review artifacts and blocks merge")
        void escalatedConflictWritesArtifactsAndBlocks(@TempDir Path repoDir) throws Exception {
            try (Git git = initRepo(repoDir)) {
                git.getRepository().getConfig()
                        .setString("user", null, "email", "developer@example.com");
                git.getRepository().getConfig().save();

                MergeManager manager = new MergeManager(repoDir);
                injectEscalationState(manager, highImpactConflict(), "feature", "main", "abc1234", "def5678");

                MergePolicy developerPolicy = MergePolicy.forRole(RoleDefinition.developer());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(developerPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertEquals(DeletionPolicy.RESTRICT_DELETIONS, resolutions.get(0).getChosenPolicy());
                assertTrue(resolutions.get(0).getReason().contains("merge blocked"),
                        "reason should indicate merge blocked, was: " + resolutions.get(0).getReason());

                assertTrue(Files.exists(repoDir.resolve(".vitruvius/notifications")
                        .resolve("feature-into-main-deleted-uuid.json")));
                assertTrue(Files.exists(repoDir.resolve(".vitruvius/reviews")
                        .resolve("feature-into-main-deleted-uuid.json")));
            }
        }

        @Test
        @DisplayName("owner APPROVE decision unblocks escalated conflict on next resolve pass")
        void ownerApproveUnblocksConflict(@TempDir Path repoDir) throws Exception {
            try (Git git = initRepo(repoDir)) {
                git.getRepository().getConfig()
                        .setString("user", null, "email", "owner@example.com");
                git.getRepository().getConfig().save();

                MergeManager manager = new MergeManager(repoDir);
                injectEscalationState(manager, highImpactConflict(), "feature", "main", "abc1234", "def5678");

                manager.submitOwnerDecision("deleted-uuid", true, "Safe to recover");

                // RESTRICT default avoids ancestor recovery in this minimal repo fixture
                MergePolicy developerPolicy = new MergePolicy(
                        DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.developer());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(developerPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertEquals(DeletionPolicy.RESTRICT_DELETIONS, resolutions.get(0).getChosenPolicy());
                assertTrue(resolutions.get(0).getReason().contains("Owner approved"));
                assertEquals("Safe to recover", resolutions.get(0).getRationale());
            }
        }

        @Test
        @DisplayName("owner DENY decision keeps merge blocked and escalates to METHODOLOGIST")
        void ownerDenyKeepsBlocked(@TempDir Path repoDir) throws Exception {
            try (Git git = initRepo(repoDir)) {
                git.getRepository().getConfig()
                        .setString("user", null, "email", "owner@example.com");
                git.getRepository().getConfig().save();

                MergeManager manager = new MergeManager(repoDir);
                injectEscalationState(manager, highImpactConflict(), "feature", "main", "abc1234", "def5678");

                manager.submitOwnerDecision("deleted-uuid", false, "Too risky");

                MergePolicy developerPolicy = MergePolicy.forRole(RoleDefinition.developer());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(developerPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertEquals(DeletionPolicy.RESTRICT_DELETIONS, resolutions.get(0).getChosenPolicy());
                assertTrue(resolutions.get(0).getReason().toLowerCase().contains("escalated to methodologist"));

                OwnerEscalationStore escalationStore = new OwnerEscalationStore(repoDir);
                assertTrue(escalationStore.load("deleted-uuid", "feature", "main").get().isEscalatedToSenior());
            }
        }

        @Test
        @DisplayName("owner APPROVE with chosen policy applies owner-selected resolution")
        void ownerApproveWithChosenPolicy(@TempDir Path repoDir) throws Exception {
            try (Git git = initRepo(repoDir)) {
                git.getRepository().getConfig()
                        .setString("user", null, "email", "owner@example.com");
                git.getRepository().getConfig().save();

                MergeManager manager = new MergeManager(repoDir);
                injectEscalationState(manager, highImpactConflict(), "feature", "main", "abc1234", "def5678");

                manager.submitOwnerDecision("deleted-uuid", true, "Accept loss",
                        DeletionPolicy.TOMBSTONE_WITH_WARNING);

                MergePolicy developerPolicy = new MergePolicy(
                        DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.developer());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(developerPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertEquals(DeletionPolicy.TOMBSTONE_WITH_WARNING, resolutions.get(0).getChosenPolicy());
                assertTrue(resolutions.get(0).getReason().contains("Owner approved"));
            }
        }

        @Test
        @DisplayName("senior METHODOLOGIST resolves after owner-escalated conflict")
        void seniorResolvesAfterOwnerEscalation(@TempDir Path repoDir) throws Exception {
            try (Git git = initRepo(repoDir)) {
                git.getRepository().getConfig()
                        .setString("user", null, "email", "methodologist@example.com");
                git.getRepository().getConfig().save();

                MergeManager manager = new MergeManager(repoDir);
                injectEscalationState(manager, highImpactConflict(), "feature", "main", "abc1234", "def5678");

                new OwnerEscalationStore(repoDir).save(OwnerEscalation.escalatedToSenior(
                        "deleted-uuid", "feature", "main", "Owner denied",
                        List.of("methodologist@example.com")));

                MergePolicy methodologistPolicy = MergePolicy.forRole(RoleDefinition.methodologist());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(methodologistPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertNotEquals("BLOCKED", resolutions.get(0).getReason());
            }
        }

        @Test
        @DisplayName("no owner response in headless mode escalates to METHODOLOGIST when timeout is zero")
        void noResponseEscalatesToSenior(@TempDir Path repoDir) throws Exception {
            String previous = System.getProperty("vitruv.owner.response.timeout.hours");
            System.setProperty("vitruv.owner.response.timeout.hours", "0");
            try (Git git = initRepo(repoDir)) {
                git.getRepository().getConfig()
                        .setString("user", null, "email", "developer@example.com");
                git.getRepository().getConfig().save();

                MergeManager manager = new MergeManager(repoDir);
                injectEscalationState(manager, highImpactConflict(), "feature", "main",
                        "abc1234", "def5678");

                MergePolicy developerPolicy = MergePolicy.forRole(RoleDefinition.developer());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(developerPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertTrue(resolutions.get(0).getReason().toLowerCase()
                        .contains("escalated to methodologist"));
            } finally {
                if (previous == null) {
                    System.clearProperty("vitruv.owner.response.timeout.hours");
                } else {
                    System.setProperty("vitruv.owner.response.timeout.hours", previous);
                }
            }
        }

        @Test
        @DisplayName("non-escalated conflicts bypass owner notification path")
        void nonEscalatedConflictResolvesDirectly(@TempDir Path repoDir) throws Exception {
            try (Git git = initRepo(repoDir)) {
                MergeManager manager = new MergeManager(repoDir);
                // Two CONSEQUENTIAL updates → impact 2 → MEDIUM → developer can approve
                injectEscalationState(manager, lowImpactConflict(), "feature", "main", "abc1234", "def5678");

                MergePolicy developerPolicy = new MergePolicy(
                        DeletionPolicy.RESTRICT_DELETIONS, RoleDefinition.developer());
                List<DeletionConflictResolver.Resolution> resolutions =
                        manager.resolveDeletionConflicts(developerPolicy, "feature");

                assertEquals(1, resolutions.size());
                assertFalse(Files.exists(repoDir.resolve(".vitruvius/notifications")));
            }
        }
    }

    private static tools.vitruv.framework.vsum.branch.data.DeletionConflict highImpactConflict() {
        return deletionConflict(
                ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL,
                Set.of("owner@example.com"));
    }

    private static tools.vitruv.framework.vsum.branch.data.DeletionConflict lowImpactConflict() {
        return deletionConflict(
                ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL,
                Set.of("owner@example.com"));
    }

    private static tools.vitruv.framework.vsum.branch.data.DeletionConflict deletionConflict(
            ChangeOrigin first, ChangeOrigin second, Set<String> owners) {
        SemanticChangeEntry u1 = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid("child-1").origin(first).build();
        SemanticChangeEntry u2 = SemanticChangeEntry.builder()
                .index(1).changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid("child-2").origin(second).build();
        return new tools.vitruv.framework.vsum.branch.data.DeletionConflict(
                "deleted-uuid", "entities::Entity",
                "feature", "main",
                List.of(u1, u2), true, ChangeOrigin.ORIGINAL,
                owners, true);
    }

    private static void injectEscalationState(MergeManager manager,
                                              tools.vitruv.framework.vsum.branch.data.DeletionConflict conflict,
                                              String sourceBranch, String targetBranch,
                                              String sourceShortSha, String targetShortSha) throws Exception {
        setField(manager, "lastDeletionConflicts", List.of(conflict));
        setField(manager, "lastSourceBranch", sourceBranch);
        setField(manager, "lastTargetBranch", targetBranch);
        setField(manager, "lastSourceShortSha", sourceShortSha);
        setField(manager, "lastTargetShortSha", targetShortSha);
        setField(manager, "sessionAuditLogger", new AuditLogger(
                getRepoRoot(manager), sourceBranch, targetBranch));
    }

    private static Path getRepoRoot(MergeManager manager) throws Exception {
        Field f = MergeManager.class.getDeclaredField("repoRoot");
        f.setAccessible(true);
        return (Path) f.get(manager);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
