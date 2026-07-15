package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.ConflictReview;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.data.SeverityThresholds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConflictReviewServiceTest {

    private static final String DELETED_UUID = "deleted-uuid";
    private static final String CHILD_UUID = "child-uuid";
    private static final String SHORT_SHA = "abc1234";

    @TempDir Path repoRoot;

    private SemanticChangelogManager changelogManager;
    private ConflictReviewService reviewService;

    @BeforeEach
    void setUp() {
        changelogManager = new SemanticChangelogManager(repoRoot);
        reviewService = new ConflictReviewService(repoRoot, changelogManager);
    }

    @Test
    @DisplayName("buildReview assembles history, state previews, and severity report")
    void buildReviewAssemblesPackage() throws IOException {
        writeChangelog("feature", SHORT_SHA, CHILD_UUID, "feature-value");
        writeChangelog("main", SHORT_SHA, DELETED_UUID, null);

        DeletionConflict conflict = conflictWithUpdates();
        ConflictReview review = reviewService.buildReview(
                conflict, SeverityThresholds.defaults(), RoleDefinition.developer(),
                "feature", "main", SHORT_SHA, SHORT_SHA);

        assertEquals(DELETED_UUID, review.getDeletedElementUuid());
        assertTrue(review.isAncestorRecoverable());
        assertEquals(ConflictSeverity.HIGH, review.getSeverityReport().getSeverity());
        assertFalse(review.getSeverityReport().isRoleCanClear());
        assertEquals("DEVELOPER", review.getSeverityReport().getRoleName());

        assertFalse(review.getHistory().isEmpty(), "history should include changelog entries");
        assertTrue(review.getHistory().stream().anyMatch(c -> CHILD_UUID.equals(c.getElementUuid())));

        assertEquals(2, review.getStatePreviews().size());
        assertEquals("old-name", review.getStatePreviews().get(0).getFrom());
        assertEquals("new-name", review.getStatePreviews().get(0).getTo());
    }

    @Test
    @DisplayName("persistReview writes artifact under .vitruvius/reviews/")
    void persistReviewWritesArtifact() throws IOException {
        DeletionConflict conflict = conflictWithUpdates();
        ConflictReview review = reviewService.buildReview(
                conflict, SeverityThresholds.defaults(), RoleDefinition.developer(),
                "feature", "main", "unknown", "unknown");

        Path written = reviewService.persistReview(review, "feature", "main");

        assertTrue(written.startsWith(repoRoot.resolve(".vitruvius").resolve("reviews")));
        assertTrue(Files.exists(written));
        assertTrue(Files.readString(written).contains(DELETED_UUID));
    }

    private DeletionConflict conflictWithUpdates() {
        SemanticChangeEntry update1 = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid(CHILD_UUID)
                .eClass("entities::Entity")
                .feature("name")
                .from("old-name")
                .to("new-name")
                .origin(ChangeOrigin.ORIGINAL)
                .build();
        SemanticChangeEntry update2 = SemanticChangeEntry.builder()
                .index(1)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid(CHILD_UUID)
                .eClass("entities::Entity")
                .feature("description")
                .from("old-desc")
                .to("new-desc")
                .origin(ChangeOrigin.ORIGINAL)
                .build();
        return new DeletionConflict(
                DELETED_UUID, "entities::Entity",
                "feature", "main",
                List.of(update1, update2), true, ChangeOrigin.ORIGINAL,
                Set.of("owner@example.com"), true);
    }

    private void writeChangelog(String branch, String shortSha, String elementUuid, String featureValue)
            throws IOException {
        SemanticChangelogManager.ChangelogDocument doc = new SemanticChangelogManager.ChangelogDocument();
        doc.formatVersion = "1.1";
        doc.commit = new SemanticChangelogManager.ChangelogDocument.CommitInfo();
        doc.commit.shortSha = shortSha;
        doc.commit.branch = branch;

        SemanticChangelogManager.ChangelogDocument.FileChangeInfo fileChange =
                new SemanticChangelogManager.ChangelogDocument.FileChangeInfo();
        fileChange.operation = "MODIFIED";
        fileChange.path = "model.xmi";
        fileChange.semanticChanges = List.of(SemanticChangeEntry.builder()
                .index(0)
                .changeType(featureValue != null ? SemanticChangeType.ATTRIBUTE_CHANGED : SemanticChangeType.ELEMENT_DELETED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid(elementUuid)
                .eClass("entities::Entity")
                .feature(featureValue != null ? "name" : null)
                .from("before")
                .to(featureValue)
                .origin(ChangeOrigin.ORIGINAL)
                .build());
        doc.fileChanges = List.of(fileChange);

        Path dir = repoRoot.resolve(".vitruvius/changelogs").resolve(branch).resolve("json");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(shortSha + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(doc));
    }
}
