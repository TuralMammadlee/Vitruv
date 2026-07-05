package tools.vitruv.framework.vsum.branch.storage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.initRepo;

class ConflictOwnershipResolverTest {

    private final ConflictOwnershipResolver resolver = new ConflictOwnershipResolver();

    @Test
    @DisplayName("uses updating-branch commit author when affected updates are ORIGINAL")
    void originalUpdateAuthor(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepo(repoDir)) {
            git.getRepository().getConfig()
                    .setString("user", null, "email", "author@example.com");
            git.getRepository().getConfig().save();

            RoleManager roleManager = new RoleManager(repoDir);
            DeletionConflict conflict = ConflictTestFixtures.deletionConflictWithOriginalUpdates(
                    "feature", "main");

            ChangelogDocument featureChangelog = changelogWithAuthor("other@example.com");
            ChangelogDocument mainChangelog = changelogWithAuthor("author@example.com");

            Set<String> owners = resolver.resolveOwners(
                    conflict, "feature", "main",
                    featureChangelog, mainChangelog,
                    Set.of("blame@example.com"), roleManager);

            assertEquals(Set.of("author@example.com"), owners);
        }
    }

    @Test
    @DisplayName("falls back to METHODOLOGIST when no semantic or blame owner exists")
    void fallbackToMethodologist(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepo(repoDir)) {
            git.getRepository().getConfig()
                    .setString("user", null, "email", "methodologist@example.com");
            git.getRepository().getConfig().save();

            RoleManager roleManager = new RoleManager(repoDir);
            DeletionConflict conflict = ConflictTestFixtures.deletionConflictWithConsequentialUpdates(
                    "feature", "main");

            Set<String> owners = resolver.resolveOwners(
                    conflict, "feature", "main",
                    null, null, Set.of(), roleManager);

            assertTrue(owners.contains("methodologist@example.com"));
        }
    }

    private static ChangelogDocument changelogWithAuthor(String email) {
        ChangelogDocument doc = new ChangelogDocument();
        doc.commit = new ChangelogDocument.CommitInfo();
        doc.commit.author = new ChangelogDocument.PersonInfo();
        doc.commit.author.email = email;
        return doc;
    }

    /** Shared fixtures for ownership tests. */
    static final class ConflictTestFixtures {
        private ConflictTestFixtures() {
        }

        static DeletionConflict deletionConflictWithOriginalUpdates(String updatingBranch, String deletingBranch) {
            SemanticChangeEntry update = SemanticChangeEntry.builder()
                    .index(0)
                    .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                    .emfType("ReplaceSingleValuedEAttribute")
                    .elementUuid("child-1")
                    .origin(ChangeOrigin.ORIGINAL)
                    .build();
            return new tools.vitruv.framework.vsum.branch.data.DeletionConflict(
                    "deleted-uuid", "entities::Entity",
                    deletingBranch, updatingBranch,
                    List.of(update), true, ChangeOrigin.CONSEQUENTIAL,
                    Set.of(), false);
        }

        static DeletionConflict deletionConflictWithConsequentialUpdates(String updatingBranch, String deletingBranch) {
            SemanticChangeEntry update = SemanticChangeEntry.builder()
                    .index(0)
                    .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                    .emfType("ReplaceSingleValuedEAttribute")
                    .elementUuid("child-1")
                    .origin(ChangeOrigin.CONSEQUENTIAL)
                    .build();
            return new tools.vitruv.framework.vsum.branch.data.DeletionConflict(
                    "deleted-uuid", "entities::Entity",
                    deletingBranch, updatingBranch,
                    List.of(update), true, ChangeOrigin.CONSEQUENTIAL,
                    Set.of(), false);
        }
    }
}
