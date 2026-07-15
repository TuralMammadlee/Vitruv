package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.JsonObject;

/**
 * Declaration of a tool that the model is allowed to call, in Ollama's
 * function-calling format:
 *
 * <pre>
 *   { "type": "function",
 *     "function": { "name": ..., "description": ..., "parameters": {json schema} } }
 * </pre>
 *
 * <p>The {@code parameters} member is a JSON-Schema object describing the tool's
 * arguments. It is carried verbatim as a {@link JsonObject} so callers can build
 * whatever schema they need without a schema-object hierarchy.
 */
public final class ToolSpec {

    private final String type = "function";
    private final Function function;

    public ToolSpec(String name, String description, JsonObject parameters) {
        this.function = new Function(name, description, parameters);
    }

    public String getType() { return type; }
    public Function getFunction() { return function; }

    /** Wire shape of the {@code function} member of a tool declaration. */
    public static final class Function {
        private final String name;
        private final String description;
        private final JsonObject parameters;

        Function(String name, String description, JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public JsonObject getParameters() { return parameters; }
    }
}
