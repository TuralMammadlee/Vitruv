package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;

import java.util.Objects;

/**
 * Tool that lets the model retrieve the recorded change history of the
 * conflicting element straight from the persisted semantic changelogs
 * ({@code .vitruvius/changelogs/<branch>/json/*.json}).
 *
 * <p>This implements the activity diagram's "LLM Analyzes Conflict Context,
 * Origin Tags &amp; Change History" step as an autonomous retrieval: instead of a
 * human pasting past commits into a prompt, the model asks for the element's
 * history and receives, per branch, every commit that touched the element with
 * the concrete changes (change type, feature, from/to values, origin tag). That
 * lets it judge, for example, which branch has been the de-facto owner of the
 * feature, or whether one side's value is the newer of the two.
 */
public final class ElementHistoryTool implements AgentTool {

    private static final String SIDE_SOURCE = "source";
    private static final String SIDE_TARGET = "target";
    private static final String SIDE_BOTH = "both";

    /** Upper bound on reported commits per branch, to keep the context small for local models. */
    private static final int MAX_COMMITS_PER_BRANCH = 10;

    private final SemanticChangelogManager changelogManager;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * @param changelogManager reader for the persisted per-branch changelogs, never null.
     */
    public ElementHistoryTool(SemanticChangelogManager changelogManager) {
        this.changelogManager = Objects.requireNonNull(changelogManager,
                "changelogManager must not be null");
    }

    @Override
    public String name() {
        return "get_element_history";
    }

    @Override
    public String description() {
        return "Returns the recorded change history of the conflicting element from the "
                + "persisted semantic changelogs: per branch, the commits that touched the "
                + "element with each change's type, feature, from/to values, and origin tag.";
    }

    @Override
    public JsonObject parametersSchema() {
        JsonObject schema = Schemas.object();
        return Schemas.property(schema, "branch", "string",
                "Which branch's history to return: 'source', 'target', or 'both' (default).");
    }

    @Override
    public String execute(JsonObject arguments, UpdateConflict conflict) {
        try {
            String side = requestedSide(arguments);
            JsonObject result = new JsonObject();
            result.addProperty("elementUuid", conflict.getElementUuid());
            JsonObject branches = new JsonObject();
            if (!SIDE_TARGET.equals(side)) {
                branches.add(conflict.getSourceBranch(),
                        branchHistory(conflict.getSourceBranch(), conflict.getElementUuid()));
            }
            if (!SIDE_SOURCE.equals(side)) {
                branches.add(conflict.getTargetBranch(),
                        branchHistory(conflict.getTargetBranch(), conflict.getElementUuid()));
            }
            result.add("branches", branches);
            return gson.toJson(result);
        } catch (Exception e) {
            return "error: could not read element history: " + e.getMessage();
        }
    }

    private String requestedSide(JsonObject arguments) {
        if (arguments != null && arguments.has("branch") && arguments.get("branch").isJsonPrimitive()) {
            String side = arguments.get("branch").getAsString().trim().toLowerCase();
            if (SIDE_SOURCE.equals(side) || SIDE_TARGET.equals(side)) {
                return side;
            }
        }
        return SIDE_BOTH;
    }

    /**
     * Collects, newest commit first, every change in the branch's changelogs
     * that touched the given element.
     */
    private JsonArray branchHistory(String branch, String elementUuid) throws Exception {
        JsonArray commits = new JsonArray();
        changelogManager.readAll(branch).stream()
                .filter(document -> touchesElement(document, elementUuid))
                .sorted((a, b) -> commitDate(b).compareTo(commitDate(a)))
                .limit(MAX_COMMITS_PER_BRANCH)
                .forEach(document -> commits.add(describeCommit(document, elementUuid)));
        return commits;
    }

    private static boolean touchesElement(ChangelogDocument document, String elementUuid) {
        return document.summary != null
                && document.summary.affectedElementUuids != null
                && document.summary.affectedElementUuids.contains(elementUuid);
    }

    private static String commitDate(ChangelogDocument document) {
        if (document.commit != null && document.commit.author != null
                && document.commit.author.date != null) {
            return document.commit.author.date;
        }
        return "";
    }

    private JsonObject describeCommit(ChangelogDocument document, String elementUuid) {
        JsonObject commit = new JsonObject();
        if (document.commit != null) {
            commit.addProperty("sha", document.commit.shortSha);
            commit.addProperty("message", document.commit.message);
            if (document.commit.author != null) {
                commit.addProperty("author", document.commit.author.name);
                commit.addProperty("date", document.commit.author.date);
            }
        }
        JsonArray changes = new JsonArray();
        if (document.fileChanges != null) {
            for (ChangelogDocument.FileChangeInfo fileChange : document.fileChanges) {
                if (fileChange.semanticChanges == null) {
                    continue;
                }
                fileChange.semanticChanges.stream()
                        .filter(entry -> elementUuid.equals(entry.getElementUuid()))
                        .forEach(entry -> changes.add(describeChange(entry)));
            }
        }
        commit.add("changesOnElement", changes);
        return commit;
    }

    private JsonObject describeChange(SemanticChangeEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("changeType", entry.getChangeType() != null ? entry.getChangeType().name() : null);
        json.addProperty("feature", entry.getFeature());
        json.addProperty("from", entry.getFrom());
        json.addProperty("to", entry.getTo());
        json.addProperty("origin", entry.getOrigin().name());
        return json;
    }
}
