package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.merge.MergeConflict;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;

/**
 * The verification oracle: lets the model ask "if I keep the source (or target)
 * side, does replaying the merge introduce any <em>other</em> conflicts
 * downstream?" It runs the candidate choice through the replay-based
 * {@link tools.vitruv.framework.vsum.branch.merge.SemanticMergeEngine} on
 * isolated scratch state and returns the resulting status, any conflicts, and
 * warnings.
 *
 * <p>This is what makes the advisor genuinely agentic rather than a one-shot
 * classifier: the model can test a hypothesis against the project's real
 * consistency machinery and react to the outcome before committing to a
 * decision. When no {@link ReplaySimulationContext} is available (e.g. the
 * offline evaluation harness or unit fixtures with no Git backing), the tool
 * reports that simulation is unavailable instead of failing, so the rest of the
 * loop is unaffected.
 */
public final class SimulateReplayTool implements AgentTool {

    private final ReplaySimulationContext context;
    private final Gson gson = new Gson();

    /**
     * @param context the simulation backend, or {@code null} if replay simulation
     *                is not available in the current environment.
     */
    public SimulateReplayTool(ReplaySimulationContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "simulate_replay";
    }

    @Override
    public String description() {
        return "Simulates keeping the chosen side and replays the merge on scratch state to "
                + "report whether it introduces other conflicts or warnings. Use this to verify a "
                + "candidate decision before submitting it.";
    }

    @Override
    public JsonObject parametersSchema() {
        JsonObject schema = Schemas.object();
        Schemas.property(schema, "choice", "string",
                "Which side to try: 'source' or 'target'.");
        JsonArray required = new JsonArray();
        required.add("choice");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject arguments, UpdateConflict conflict) {
        if (context == null) {
            return "{\"available\":false,\"note\":\"replay simulation is not available in this "
                    + "environment; decide from the other tools\"}";
        }

        boolean chooseSource;
        try {
            String choice = arguments.get("choice").getAsString().trim().toLowerCase();
            if ("source".equals(choice)) {
                chooseSource = true;
            } else if ("target".equals(choice)) {
                chooseSource = false;
            } else {
                return "error: 'choice' must be 'source' or 'target', got '" + choice + "'";
            }
        } catch (RuntimeException e) {
            return "error: missing or invalid 'choice' argument";
        }

        try {
            SemanticMergeResult result = context.simulate(conflict, chooseSource);
            return summarize(result, chooseSource);
        } catch (Exception e) {
            return "error: replay simulation failed: " + e.getMessage();
        }
    }

    private String summarize(SemanticMergeResult result, boolean chooseSource) {
        JsonObject json = new JsonObject();
        json.addProperty("available", true);
        json.addProperty("triedSide", chooseSource ? "source" : "target");
        json.addProperty("status", result.getStatus().name());
        json.addProperty("success", result.isSuccess());
        json.addProperty("appliedChangeCount", result.getAppliedChanges().size());
        json.add("otherConflicts", renderConflicts(result.getConflicts()));
        json.add("warnings", renderConflicts(result.getWarnings()));
        return gson.toJson(json);
    }

    private JsonArray renderConflicts(java.util.List<MergeConflict> conflicts) {
        JsonArray array = new JsonArray();
        if (conflicts == null) {
            return array;
        }
        for (MergeConflict conflict : conflicts) {
            JsonObject json = new JsonObject();
            json.addProperty("type", conflict.getType().name());
            json.addProperty("elementUuid", conflict.getElementUuid());
            json.addProperty("feature", conflict.getConflictingFeature());
            array.add(json);
        }
        return array;
    }
}
