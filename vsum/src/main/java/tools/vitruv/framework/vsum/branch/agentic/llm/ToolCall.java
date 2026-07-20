package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.JsonObject;

/**
 * A single tool invocation requested by the model in an assistant message.
 *
 * <p>Ollama returns the tool name and a JSON object of arguments. Arguments are
 * kept as a raw {@link JsonObject} so the executing tool can read exactly the
 * fields it declared in its parameter schema without an intermediate typed DTO.
 */
public final class ToolCall {

    private String id;
    private Function function;

    public Function getFunction() { return function; }

    /**
     * Provider-assigned call id. Present in OpenAI-compatible responses (used to
     * pair the tool result back to this call); {@code null} for Ollama and for
     * content-parsed calls.
     */
    public String id() {
        return id;
    }

    public String name() {
        return function == null ? null : function.name;
    }

    /** Returns the argument object, never null (an empty object when the model sent none). */
    public JsonObject arguments() {
        if (function == null || function.arguments == null) {
            return new JsonObject();
        }
        return function.arguments;
    }

    /** Builds a tool call from a parsed name and arguments (content-based fallback). */
    public static ToolCall of(String name, JsonObject arguments) {
        return of(null, name, arguments);
    }

    /** Builds a tool call with an explicit provider call id (OpenAI-compatible parsing). */
    public static ToolCall of(String id, String name, JsonObject arguments) {
        ToolCall call = new ToolCall();
        call.id = id;
        call.function = new Function();
        call.function.name = name;
        call.function.arguments = arguments == null ? new JsonObject() : arguments;
        return call;
    }

    /** Wire shape of the {@code function} member of a tool call. */
    public static final class Function {
        private String name;
        private JsonObject arguments;

        public String getName() { return name; }
        public JsonObject getArguments() { return arguments; }
    }
}
