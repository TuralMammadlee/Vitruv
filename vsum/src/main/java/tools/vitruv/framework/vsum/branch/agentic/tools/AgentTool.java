package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.JsonObject;
import tools.vitruv.framework.vsum.branch.agentic.llm.ToolSpec;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

/**
 * A capability the agentic advisor exposes to the model as a callable tool.
 *
 * <p>Each tool holds only long-lived, conflict-independent dependencies (e.g. a
 * {@code ResolutionHistory} or a merge engine); the specific
 * {@link UpdateConflict} under consideration is passed to {@link #execute} per
 * call. This keeps a single tool instance safely reusable across the many
 * conflicts of a merge and matches the side-effect-free contract of the
 * enclosing {@code ConflictResolutionAdvisor}.
 *
 * <p>Implementations must never throw from {@link #execute}: a tool that fails
 * should return an explanatory string so the model can react, rather than
 * aborting the whole reasoning loop.
 */
public interface AgentTool {

    /** Stable tool name the model uses to call it (snake_case, no spaces). */
    String name();

    /** One-line description shown to the model to help it decide when to call the tool. */
    String description();

    /** JSON-Schema object describing this tool's arguments (may be an empty-properties object). */
    JsonObject parametersSchema();

    /**
     * Executes the tool for the given conflict and returns a plain-text (or JSON)
     * result to feed back to the model. Must not throw.
     *
     * @param arguments the arguments the model supplied, never null.
     * @param conflict  the conflict currently being resolved, never null.
     * @return the tool result to append to the conversation.
     */
    String execute(JsonObject arguments, UpdateConflict conflict);

    /** Builds the {@link ToolSpec} declaration sent to the model for this tool. */
    default ToolSpec toSpec() {
        return new ToolSpec(name(), description(), parametersSchema());
    }
}
