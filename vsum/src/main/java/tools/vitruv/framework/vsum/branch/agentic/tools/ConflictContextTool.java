package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

/**
 * Tool that returns the full structured context of the conflict under
 * consideration: the two competing {@link SemanticChangeEntry change entries}
 * (element UUID, EClass, feature, old/new values, {@code ChangeOrigin},
 * change type) plus the derived {@code OriginPermutation},
 * {@code FundamentalConflictType} and {@code ConflictSeverity}.
 *
 * <p>The same information is already summarised in the advisor's opening prompt;
 * exposing it as a tool lets the model re-read the exact machine-readable facts
 * on demand instead of relying on the prose summary, which measurably reduces
 * hallucinated field values in local models.
 */
public final class ConflictContextTool implements AgentTool {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String name() {
        return "get_conflict_context";
    }

    @Override
    public String description() {
        return "Returns the full structured details of the current update conflict: "
                + "both sides' change entries (element, feature, from/to values, origin, change type) "
                + "and the derived origin permutation, fundamental type, and severity.";
    }

    @Override
    public JsonObject parametersSchema() {
        return Schemas.noArgs();
    }

    @Override
    public String execute(JsonObject arguments, UpdateConflict conflict) {
        try {
            return gson.toJson(describe(conflict));
        } catch (RuntimeException e) {
            return "error: could not serialize conflict context: " + e.getMessage();
        }
    }

    /** Builds the JSON view of the conflict returned to the model. */
    private JsonObject describe(UpdateConflict conflict) {
        JsonObject root = new JsonObject();
        root.addProperty("elementUuid", conflict.getElementUuid());
        root.addProperty("eClass", conflict.getEClass());
        root.addProperty("feature", conflict.getFeatureName());
        root.addProperty("sourceBranch", conflict.getSourceBranch());
        root.addProperty("targetBranch", conflict.getTargetBranch());
        root.addProperty("originPermutation", conflict.getOriginPermutation().name());
        root.addProperty("fundamentalType", conflict.getFundamentalType().name());
        root.addProperty("severity", conflict.getSeverity().name());
        root.add("source", describeEntry(conflict.getSourceEntry()));
        root.add("target", describeEntry(conflict.getTargetEntry()));
        return root;
    }

    private JsonObject describeEntry(SemanticChangeEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("changeType", entry.getChangeType().name());
        json.addProperty("origin", entry.getOrigin().name());
        json.addProperty("elementUuid", entry.getElementUuid());
        json.addProperty("eClass", entry.getEClass());
        json.addProperty("feature", entry.getFeature());
        json.addProperty("from", entry.getFrom());
        json.addProperty("to", entry.getTo());
        return json;
    }
}
