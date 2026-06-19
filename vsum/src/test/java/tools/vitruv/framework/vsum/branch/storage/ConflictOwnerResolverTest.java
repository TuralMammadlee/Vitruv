package tools.vitruv.framework.vsum.branch.storage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.GitTestHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictOwnerResolverTest {

    @Test
    @DisplayName("Returns empty map for empty conflicts")
    void emptyConflictsReturnEmptyMap(@TempDir Path repoDir) throws Exception {
        try (Git git = GitTestHelper.initRepo(repoDir)) {
            ConflictOwnerResolver resolver = new ConflictOwnerResolver();
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();

            Map<String, Set<String>> owners = resolver.resolveConflictOwners(
                    git.getRepository(), head.getId(), head.getId(), Map.of());

            assertTrue(owners.isEmpty());
        }
    }

    @Test
    @DisplayName("Collects owners from both source and target revisions")
    void collectsOwnersFromBothRevisions(@TempDir Path repoDir) throws Exception {
        try (Git git = GitTestHelper.initRepo(repoDir)) {
            Path file = repoDir.resolve("system.model");
            Files.writeString(file, "<System v='base'/>");
            git.add().addFilepattern("system.model").call();
            git.commit()
                    .setMessage("Base system file")
                    .setAuthor("Base User", "base@example.com")
                    .call();

            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            Files.writeString(file, "<System v='feature'/>");
            git.add().addFilepattern("system.model").call();
            RevCommit sourceCommit = git.commit()
                    .setMessage("Feature change")
                    .setAuthor("Feature User", "feature@example.com")
                    .call();

            git.checkout().setName("master").call();
            Files.writeString(file, "<System v='master'/>");
            git.add().addFilepattern("system.model").call();
            RevCommit targetCommit = git.commit()
                    .setMessage("Master change")
                    .setAuthor("Main User", "main@example.com")
                    .call();

            ConflictOwnerResolver resolver = new ConflictOwnerResolver();
            Map<String, Set<String>> ownersByFile = resolver.resolveConflictOwners(
                    git.getRepository(),
                    sourceCommit.getId(),
                    targetCommit.getId(),
                    Map.of("system.model", new int[][]{{1, 2}}));

            assertTrue(ownersByFile.containsKey("system.model"));
            assertEquals(Set.of("feature@example.com", "main@example.com"), ownersByFile.get("system.model"));
        }
    }
}
