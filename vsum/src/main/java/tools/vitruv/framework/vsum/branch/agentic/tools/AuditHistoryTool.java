package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ResolutionHistory;

import java.util.List;
import java.util.Objects;

/**
 * Tool that lets the model retrieve the project's own past resolution outcomes
 * for conflicts comparable to the current one, keyed by the stable
 * {@link ResolutionHistory#signature(UpdateConflict) signature}
 * ({@code eClass|feature|originPermutation}).
 *
 * <p>This is the retrieval step of a retrieval-augmented agent: rather than
 * hiding the evidence inside a Beta-Bernoulli posterior (as
 * {@code AuditHistoryAdvisor} does), it surfaces the raw counts as an explicit,
 * inspectable tool call the model can weigh against the structural facts. The
 * returned Beta posterior is included as a convenience so the model does not
 * have to do the arithmetic itself.
 */
public final class AuditHistoryTool implements AgentTool {

    /** Maximum number of past decisions echoed back with their rationales. */
    private static final int MAX_RECENT_DECISIONS = 5;

    private final ResolutionHistory history;
    private final Gson gson = new Gson();

    public AuditHistoryTool(ResolutionHistory history) {
        this.history = Objects.requireNonNull(history, "history must not be null");
    }

    @Override
    public String name() {
        return "get_resolution_history";
    }

    @Override
    public String description() {
        return "Returns how often the source side versus the target side was kept in past "
                + "resolutions of comparable conflicts (same element type, feature, and origin "
                + "permutation), plus the resulting posterior probability that the source side wins.";
    }

    @Override
    public JsonObject parametersSchema() {
        return Schemas.noArgs();
    }

    @Override
    public String execute(JsonObject arguments, UpdateConflict conflict) {
        try {
            ResolutionHistory.Outcomes outcomes = history.outcomesFor(conflict);
            int total = outcomes.total();

            JsonObject result = new JsonObject();
            result.addProperty("signature", ResolutionHistory.signature(conflict));
            result.addProperty("sourceWins", outcomes.sourceWins());
            result.addProperty("targetWins", outcomes.targetWins());
            result.addProperty("totalObservations", total);

            if (total == 0) {
                result.addProperty("note", "No prior resolutions recorded for this signature; "
                        + "decide from the structural facts instead.");
            } else {
                // Uniform Beta(1,1) prior, matching AuditHistoryAdvisor's default.
                double pSource = (outcomes.sourceWins() + 1.0) / (total + 2.0);
                result.addProperty("posteriorPSource", round(pSource));
                result.addProperty("historicallyPreferred", pSource >= 0.5 ? "source" : "target");
                result.add("recentDecisions", recentDecisions(conflict));
            }
            return gson.toJson(result);
        } catch (RuntimeException e) {
            return "error: could not read resolution history: " + e.getMessage();
        }
    }

    /**
     * Echoes the most recent comparable decisions with their recorded reason and
     * human rationale, so the model can weigh the qualitative evidence and not
     * just the counts. Empty when the backing history keeps only tallies.
     */
    private JsonArray recentDecisions(UpdateConflict conflict) {
        JsonArray array = new JsonArray();
        List<ResolutionHistory.RecordedDecision> decisions =
                history.decisionsFor(conflict, MAX_RECENT_DECISIONS);
        for (ResolutionHistory.RecordedDecision decision : decisions) {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", decision.timestamp());
            json.addProperty("chosenSide", decision.chosenSide());
            json.addProperty("reason", decision.reason());
            json.addProperty("rationale", decision.rationale());
            array.add(json);
        }
        return array;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
