package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UpdateConflictAnalyzer}.
 */
class UpdateConflictAnalyzerTest {

    private UpdateConflictAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new UpdateConflictAnalyzer();
    }

    @Test
    @DisplayName("No conflict when changelogs modify different elements")
    void noConflictDifferentElements() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-B", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("source", "target", source, target);
        assertTrue(conflicts.isEmpty());
    }

    @Test
    @DisplayName("No conflict when same element but different features")
    void noConflictDifferentFeatures() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "age", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("source", "target", source, target);
        assertTrue(conflicts.isEmpty());
    }

    @Test
    @DisplayName("Detects conflict when same UUID + same feature")
    void detectsSameUuidSameFeature() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_SET, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);

        assertEquals(1, conflicts.size());
        UpdateConflict c = conflicts.get(0);
        assertEquals("uuid-A", c.getElementUuid());
        assertEquals("name", c.getFeatureName());
        assertEquals("feat", c.getSourceBranch());
        assertEquals("main", c.getTargetBranch());
        assertEquals(ConflictSeverity.MEDIUM, c.getSeverity());
    }

    @Test
    @DisplayName("Detects multiple conflicts on different features of the same element")
    void multipleConflictsSameElement() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL),
                entry("uuid-A", "email", SemanticChangeType.ATTRIBUTE_SET, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL),
                entry("uuid-A", "email", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertEquals(2, conflicts.size());
    }

    @Test
    @DisplayName("Detects ORIGINAL vs CONSEQUENTIAL auto-resolution preference")
    void originalVsConsequential() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.CONSEQUENTIAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertEquals(1, conflicts.size());

        UpdateConflict c = conflicts.get(0);
        assertTrue(c.isOriginalVsConsequential());
        assertEquals("feat", c.getPreferredBranch()); // Source is ORIGINAL
        assertNotNull(c.getPreferredEntry());
    }

    @Test
    @DisplayName("Same origin means no auto-preference")
    void sameOriginNoPreference() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertEquals(1, conflicts.size());

        UpdateConflict c = conflicts.get(0);
        assertFalse(c.isOriginalVsConsequential());
        assertNull(c.getPreferredEntry());
        assertNull(c.getPreferredBranch());
    }

    @Test
    @DisplayName("Ignores lifecycle changes (create/delete)")
    void ignoresLifecycleChanges() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", null, SemanticChangeType.ELEMENT_CREATED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", null, SemanticChangeType.ELEMENT_DELETED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertTrue(conflicts.isEmpty()); // Lifecycle changes are not "update" conflicts
    }

    @Test
    @DisplayName("Ignores entries with null feature")
    void ignoresNullFeature() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", null, SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", null, SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertTrue(conflicts.isEmpty());
    }

    @Test
    @DisplayName("Null changelogs return empty list")
    void nullChangelogs() {
        assertTrue(analyzer.analyze("a", "b", null, null).isEmpty());
        assertTrue(analyzer.analyze("a", "b", buildChangelog(), null).isEmpty());
        assertTrue(analyzer.analyze("a", "b", null, buildChangelog()).isEmpty());
    }

    @Test
    @DisplayName("No duplicate conflicts for same UUID+feature")
    void noDuplicates() {
        // Source has two changes on same UUID+feature
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL),
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_SET, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "name", SemanticChangeType.ATTRIBUTE_CHANGED, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertEquals(1, conflicts.size()); // Only one conflict per UUID+feature
    }

    @Test
    @DisplayName("Reference changes are also detected")
    void referenceChanges() {
        ChangelogDocument source = buildChangelog(
                entry("uuid-A", "parent", SemanticChangeType.REFERENCE_CHANGED, ChangeOrigin.ORIGINAL));
        ChangelogDocument target = buildChangelog(
                entry("uuid-A", "parent", SemanticChangeType.REFERENCE_SET, ChangeOrigin.ORIGINAL));

        List<UpdateConflict> conflicts = analyzer.analyze("feat", "main", source, target);
        assertEquals(1, conflicts.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private SemanticChangeEntry entry(String uuid, String feature,
                                      SemanticChangeType type, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType(type.name())
                .elementUuid(uuid)
                .eClass("entities::Entity")
                .feature(feature)
                .origin(origin)
                .build();
    }

    private ChangelogDocument buildChangelog(SemanticChangeEntry... entries) {
        ChangelogDocument doc = new ChangelogDocument();
        doc.fileChanges = new ArrayList<>();
        if (entries.length > 0) {
            ChangelogDocument.FileChangeInfo fc = new ChangelogDocument.FileChangeInfo();
            fc.semanticChanges = List.of(entries);
            doc.fileChanges.add(fc);
        }
        return doc;
    }
}
