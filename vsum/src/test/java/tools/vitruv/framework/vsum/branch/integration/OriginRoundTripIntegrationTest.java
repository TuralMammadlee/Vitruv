package tools.vitruv.framework.vsum.branch.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.OriginPermutation;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.FileChangeInfo;
import tools.vitruv.framework.vsum.branch.storage.UpdateConflictAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test guarding the origin-tag persistence pipeline.
 *
 * <p>Background: the auto-resolution Tier&nbsp;1 rule ("ORIGINAL over CONSEQUENTIAL")
 * depends on the {@link ChangeOrigin} surviving the trip
 * <em>buffer → JSON file → analyzer → conflict</em>.  A regression that loses
 * the tag in transit silently disables the rule — every conflict collapses to
 * {@link OriginPermutation#UNKNOWN_UNKNOWN} and falls through to manual
 * resolution.  No unit test on {@code SemanticChangelogManager} alone catches
 * that; it has to be observed end-to-end.
 *
 * <p>The test writes a JSON changelog in the on-disk format used by
 * {@link SemanticChangelogManager}, then reads it back through the public
 * {@code read} method and feeds the result into {@link UpdateConflictAnalyzer}.
 * The assertion is that the resulting {@link UpdateConflict} reports the
 * correct {@link OriginPermutation} — i.e. the origin tags survived.
 */
@Tag("integration")
class OriginRoundTripIntegrationTest {

    @Test
    @DisplayName("ORIGINAL origin survives write → read → analyzer")
    void originalOriginSurvivesRoundTrip(@TempDir Path repoRoot) throws IOException {
        writeChangelog(repoRoot, "feature", "abc1234",
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "elt-1", "name", ChangeOrigin.ORIGINAL));
        writeChangelog(repoRoot, "main", "def5678",
                entry(SemanticChangeType.ATTRIBUTE_CHANGED, "elt-1", "name", ChangeOrigin.CONSEQUENTIAL));

        SemanticChangelogManager mgr = new SemanticChangelogManager(repoRoot);
        ChangelogDocument source = mgr.read("feature", "abc1234");
        ChangelogDocument target = mgr.read("main", "def5678");

        // Round-trip sanity: origin tag survived deserialisation
        assertEquals(ChangeOrigin.ORIGINAL,
                source.fileChanges.get(0).semanticChanges.get(0).getOrigin());
        assertEquals(ChangeOrigin.CONSEQUENTIAL,
                target.fileChanges.get(0).semanticChanges.get(0).getOrigin());

        // End-to-end: analyzer derives the right permutation
        List<UpdateConflict> conflicts = new UpdateConflictAnalyzer()
                .analyze("feature", "main", source, target);
        assertEquals(1, conflicts.size());
        assertEquals(OriginPermutation.O_C, conflicts.get(0).getOriginPermutation(),
                "permutation must reflect the persisted origin tags — if this fails, "
                        + "the buffer→changelog pipeline has dropped origin information again");
        assertTrue(conflicts.get(0).isOriginalVsConsequential(),
                "mixed-origin conflicts must be auto-resolvable in favour of ORIGINAL");
    }

    @Test
    @DisplayName("Unknown origin value in JSON falls back to UNKNOWN (forward compatibility)")
    void unknownOriginValueFallsBack(@TempDir Path repoRoot) throws IOException {
        // Hand-craft JSON with a bogus origin value to verify the deserialiser's safety net
        Path jsonDir = repoRoot.resolve(".vitruvius").resolve("changelogs").resolve("feature").resolve("json");
        Files.createDirectories(jsonDir);
        Files.writeString(jsonDir.resolve("abc1234.json"),
                "{\n"
                        + "  \"formatVersion\": \"1.1\",\n"
                        + "  \"commit\": {\"sha\": \"abc1234abc1234abc1234\", \"branch\": \"feature\"},\n"
                        + "  \"fileChanges\": [{\n"
                        + "    \"operation\": \"MODIFIED\",\n"
                        + "    \"path\": \"x.xmi\",\n"
                        + "    \"semanticChanges\": [{\n"
                        + "      \"index\": 0,\n"
                        + "      \"changeType\": \"ATTRIBUTE_CHANGED\",\n"
                        + "      \"emfType\": \"ReplaceSingleValuedEAttribute\",\n"
                        + "      \"elementUuid\": \"elt-1\",\n"
                        + "      \"feature\": \"name\",\n"
                        + "      \"origin\": \"FROM_THE_FUTURE\"\n"
                        + "    }]\n"
                        + "  }]\n"
                        + "}");

        ChangelogDocument doc = new SemanticChangelogManager(repoRoot).read("feature", "abc1234");
        assertEquals(ChangeOrigin.UNKNOWN,
                doc.fileChanges.get(0).semanticChanges.get(0).getOrigin(),
                "unknown origin literals must be tolerated, not crash deserialisation");
    }

    @Test
    @DisplayName("Missing origin field in JSON defaults to UNKNOWN (backward compatibility)")
    void missingOriginFieldDefaultsToUnknown(@TempDir Path repoRoot) throws IOException {
        Path jsonDir = repoRoot.resolve(".vitruvius").resolve("changelogs").resolve("feature").resolve("json");
        Files.createDirectories(jsonDir);
        Files.writeString(jsonDir.resolve("abc1234.json"),
                "{\n"
                        + "  \"formatVersion\": \"1.0\",\n"
                        + "  \"commit\": {\"sha\": \"abc1234abc1234abc1234\", \"branch\": \"feature\"},\n"
                        + "  \"fileChanges\": [{\n"
                        + "    \"operation\": \"MODIFIED\",\n"
                        + "    \"path\": \"x.xmi\",\n"
                        + "    \"semanticChanges\": [{\n"
                        + "      \"index\": 0,\n"
                        + "      \"changeType\": \"ATTRIBUTE_CHANGED\",\n"
                        + "      \"emfType\": \"ReplaceSingleValuedEAttribute\",\n"
                        + "      \"elementUuid\": \"elt-1\",\n"
                        + "      \"feature\": \"name\"\n"
                        + "    }]\n"
                        + "  }]\n"
                        + "}");

        ChangelogDocument doc = new SemanticChangelogManager(repoRoot).read("feature", "abc1234");
        assertEquals(ChangeOrigin.UNKNOWN,
                doc.fileChanges.get(0).semanticChanges.get(0).getOrigin(),
                "old changelogs without origin field must default to UNKNOWN");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Writes a JSON changelog into the canonical location using a Gson
     * configured to match the one inside {@link SemanticChangelogManager}.
     * Reproducing the configuration here lets the test verify the manager's
     * read path on data written by an independent serializer — a stronger
     * round-trip guarantee than write-then-read with the same instance.
     */
    private static void writeChangelog(Path repoRoot, String branch, String shortSha,
                                        SemanticChangeEntry... entries) throws IOException {
        Path jsonDir = repoRoot.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
        Files.createDirectories(jsonDir);

        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = "1.1";
        doc.commit = new ChangelogDocument.CommitInfo();
        doc.commit.sha = shortSha + "0000000000000000";
        doc.commit.shortSha = shortSha;
        doc.commit.branch = branch;

        FileChangeInfo fc = new FileChangeInfo();
        fc.operation = "MODIFIED";
        fc.path = "model/test.xmi";
        fc.semanticChanges = new ArrayList<>(List.of(entries));
        doc.fileChanges = new ArrayList<>(List.of(fc));

        Gson gson = new GsonBuilder().setPrettyPrinting()
                .registerTypeAdapter(ChangeOrigin.class,
                        (JsonSerializer<ChangeOrigin>) (src, t, c) -> new JsonPrimitive(src.name()))
                .registerTypeAdapter(ChangeOrigin.class,
                        (JsonDeserializer<ChangeOrigin>) (j, t, c) -> ChangeOrigin.valueOf(j.getAsString()))
                .create();
        Files.writeString(jsonDir.resolve(shortSha + ".json"), gson.toJson(doc));
    }

    private static SemanticChangeEntry entry(SemanticChangeType type, String elementUuid,
                                              String feature, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid(elementUuid)
                .eClass("entities::Entity")
                .feature(feature)
                .origin(origin)
                .build();
    }
}
