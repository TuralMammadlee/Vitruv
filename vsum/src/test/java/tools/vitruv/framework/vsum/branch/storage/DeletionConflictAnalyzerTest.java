package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.FileChangeInfo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeletionConflictAnalyzer}.
 */
class DeletionConflictAnalyzerTest {

    private DeletionConflictAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DeletionConflictAnalyzer();
    }

    @Test
    @DisplayName("No conflicts when changelogs are null")
    void noConflictsWhenChangelogsAreNull() {
        var result = analyzer.analyze("source", "target", null, null, true);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("No conflicts when changelogs have no deletions")
    void noConflictsWhenNoDeletes() {
        var source = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-1", null, ChangeOrigin.ORIGINAL));
        var target = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-2", null, ChangeOrigin.ORIGINAL));

        var result = analyzer.analyze("source", "target", source, target, true);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Detects delete-vs-update conflict on same element")
    void detectsDeleteVsUpdateOnSameElement() {
        // Source deletes uuid-1, target updates uuid-1
        var source = buildChangelog(entry(SemanticChangeType.ELEMENT_DELETED, "uuid-1", null, ChangeOrigin.CONSEQUENTIAL));
        var target = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-1", null, ChangeOrigin.ORIGINAL));

        var result = analyzer.analyze("source", "target", source, target, true);
        assertEquals(1, result.size());

        DeletionConflict conflict = result.get(0);
        assertEquals("uuid-1", conflict.getDeletedElementUuid());
        assertEquals("source", conflict.getDeletingBranch());
        assertEquals("target", conflict.getUpdatingBranch());
        assertEquals(1, conflict.getLostUpdateCount());
        assertTrue(conflict.isAncestorAvailable());
    }

    @Test
    @DisplayName("Detects delete-vs-update on child elements via containerUuid")
    void detectsDeleteVsUpdateOnChildElements() {
        // Source deletes uuid-parent, target updates a child whose containerUuid is uuid-parent
        var source = buildChangelog(entry(SemanticChangeType.ELEMENT_DELETED, "uuid-parent", null, ChangeOrigin.ORIGINAL));
        var target = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-child", "uuid-parent", ChangeOrigin.ORIGINAL));

        var result = analyzer.analyze("source", "target", source, target, true);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getLostUpdateCount());
    }

    @Test
    @DisplayName("Detects conflicts symmetrically (target deletes, source updates)")
    void detectsConflictsSymmetrically() {
        // Target deletes, source updates
        var source = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-1", null, ChangeOrigin.ORIGINAL));
        var target = buildChangelog(entry(SemanticChangeType.ELEMENT_DELETED, "uuid-1", null, ChangeOrigin.CONSEQUENTIAL));

        var result = analyzer.analyze("source", "target", source, target, true);
        assertEquals(1, result.size());
        assertEquals("target", result.get(0).getDeletingBranch());
        assertEquals("source", result.get(0).getUpdatingBranch());
    }

    @Test
    @DisplayName("Multiple affected updates are counted correctly")
    void multipleAffectedUpdates() {
        var source = buildChangelog(entry(SemanticChangeType.ELEMENT_DELETED, "uuid-1", null, ChangeOrigin.ORIGINAL));
        var target = buildChangelog(
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-1", null, ChangeOrigin.ORIGINAL),
                entry(SemanticChangeType.ATTRIBUTE_SET, "uuid-1", null, ChangeOrigin.ORIGINAL),
                entry(SemanticChangeType.REFERENCE_SET, "uuid-child", "uuid-1", ChangeOrigin.ORIGINAL)
        );

        var result = analyzer.analyze("source", "target", source, target, true);
        assertEquals(1, result.size());
        assertEquals(3, result.get(0).getLostUpdateCount());
    }

    @Test
    @DisplayName("No conflict when deletion and update target different elements")
    void noConflictWhenDifferentElements() {
        var source = buildChangelog(entry(SemanticChangeType.ELEMENT_DELETED, "uuid-1", null, ChangeOrigin.ORIGINAL));
        var target = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-2", null, ChangeOrigin.ORIGINAL));

        var result = analyzer.analyze("source", "target", source, target, true);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Consequential deletion vs original update is detected")
    void consequentialDeletionVsOriginalUpdateIsDetected() {
        var source = buildChangelog(entry(SemanticChangeType.ELEMENT_DELETED, "uuid-1", null, ChangeOrigin.CONSEQUENTIAL));
        var target = buildChangelog(entry(SemanticChangeType.ATTRIBUTE_CHANGED, "uuid-1", null, ChangeOrigin.ORIGINAL));

        var result = analyzer.analyze("source", "target", source, target, true);
        assertEquals(1, result.size());
        assertTrue(result.get(0).isConsequentialDeletionVsOriginalUpdates());
    }

    // Helper methods

    private SemanticChangeEntry entry(SemanticChangeType type, String elementUuid,
                                      String containerUuid, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType("TestType")
                .elementUuid(elementUuid)
                .containerUuid(containerUuid)
                .origin(origin)
                .build();
    }

    private ChangelogDocument buildChangelog(SemanticChangeEntry... entries) {
        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = "1.1";
        doc.fileChanges = new ArrayList<>();
        FileChangeInfo fc = new FileChangeInfo();
        fc.operation = "MODIFIED";
        fc.path = "test.xmi";
        fc.semanticChanges = List.of(entries);
        doc.fileChanges.add(fc);
        doc.summary = new ChangelogDocument.Summary();
        doc.summary.totalFileChanges = 1;
        doc.summary.totalSemanticChanges = entries.length;
        doc.summary.affectedElementUuids = new ArrayList<>();
        return doc;
    }
}
