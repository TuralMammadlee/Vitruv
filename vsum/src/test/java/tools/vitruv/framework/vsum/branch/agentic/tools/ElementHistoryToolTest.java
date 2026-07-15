package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ElementHistoryTool} autonomously retrieves an element's
 * recorded change history from the persisted changelogs on disk — the step that
 * lets the LLM gather change history itself rather than being handed it.
 */
class ElementHistoryToolTest {

    private static SemanticChangeEntry entry(ChangeOrigin origin, String to) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValued")
                .elementUuid("uuid-1")
                .eClass("entities::Entity")
                .feature("name")
                .from("old")
                .to(to)
                .origin(origin)
                .build();
    }

    private static UpdateConflict conflict() {
        return new UpdateConflict("uuid-1", "entities::Entity", "name", "featureBranch", "main",
                entry(ChangeOrigin.ORIGINAL, "srcValue"), entry(ChangeOrigin.ORIGINAL, "tgtValue"));
    }

    /** Writes a minimal changelog JSON matching what the manager reads back. */
    private static void writeChangelog(Path repo, String branch, String sha,
                                       String elementUuid, String to) throws IOException {
        Path dir = repo.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
        Files.createDirectories(dir);
        String json = """
                {
                  "formatVersion": "1.1",
                  "commit": { "sha": "%s", "shortSha": "%s", "branch": "%s",
                              "author": { "name": "Alice", "date": "2026-07-10T10:00:00" },
                              "message": "edit name", "parentShas": [] },
                  "fileChanges": [ { "operation": "MODIFIED", "path": "model/x.xmi",
                      "semanticChanges": [ { "index": 0, "changeType": "ATTRIBUTE_CHANGED",
                          "elementUuid": "%s", "eClass": "entities::Entity", "feature": "name",
                          "from": "old", "to": "%s", "origin": "ORIGINAL" } ] } ],
                  "summary": { "totalFileChanges": 1, "totalSemanticChanges": 1,
                               "affectedElementUuids": [ "%s" ] }
                }
                """.formatted(sha, sha, branch, elementUuid, to, elementUuid);
        Files.writeString(dir.resolve(sha + ".json"), json);
    }

    @Test
    @DisplayName("returns per-branch commit history for the conflicting element")
    void returnsHistoryForBothBranches(@TempDir Path repo) throws IOException {
        writeChangelog(repo, "featureBranch", "aaaaaaa", "uuid-1", "srcValue");
        writeChangelog(repo, "main", "bbbbbbb", "uuid-1", "tgtValue");

        ElementHistoryTool tool = new ElementHistoryTool(new SemanticChangelogManager(repo));
        String result = tool.execute(new JsonObject(), conflict());

        assertFalse(result.startsWith("error:"), result);
        JsonObject root = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("uuid-1", root.get("elementUuid").getAsString());
        JsonObject branches = root.getAsJsonObject("branches");
        assertTrue(branches.has("featureBranch"));
        assertTrue(branches.has("main"));
        assertEquals(1, branches.getAsJsonArray("featureBranch").size());
        JsonObject commit = branches.getAsJsonArray("featureBranch").get(0).getAsJsonObject();
        assertEquals("srcValue",
                commit.getAsJsonArray("changesOnElement").get(0).getAsJsonObject().get("to").getAsString());
    }

    @Test
    @DisplayName("honours the branch=source argument")
    void restrictsToSource(@TempDir Path repo) throws IOException {
        writeChangelog(repo, "featureBranch", "aaaaaaa", "uuid-1", "srcValue");
        writeChangelog(repo, "main", "bbbbbbb", "uuid-1", "tgtValue");

        ElementHistoryTool tool = new ElementHistoryTool(new SemanticChangelogManager(repo));
        JsonObject args = new JsonObject();
        args.addProperty("branch", "source");
        JsonObject branches = JsonParser.parseString(tool.execute(args, conflict()))
                .getAsJsonObject().getAsJsonObject("branches");

        assertTrue(branches.has("featureBranch"));
        assertFalse(branches.has("main"));
    }

    @Test
    @DisplayName("returns empty branch histories when no changelogs exist")
    void emptyWhenNoChangelogs(@TempDir Path repo) {
        ElementHistoryTool tool = new ElementHistoryTool(new SemanticChangelogManager(repo));
        JsonObject branches = JsonParser.parseString(tool.execute(new JsonObject(), conflict()))
                .getAsJsonObject().getAsJsonObject("branches");
        assertEquals(0, branches.getAsJsonArray("featureBranch").size());
        assertEquals(0, branches.getAsJsonArray("main").size());
    }
}
