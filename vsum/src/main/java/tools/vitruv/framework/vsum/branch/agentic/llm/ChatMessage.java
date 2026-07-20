package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * One message in an Ollama {@code /api/chat} conversation.
 *
 * <p>Roles follow the Ollama/OpenAI convention: {@code system}, {@code user},
 * {@code assistant}, and {@code tool}. Assistant messages may carry
 * {@link #toolCalls}; tool messages carry the string result of executing a tool.
 * This is a plain data holder serialized by Gson, so field names must match the
 * wire format (see {@link #toolCalls} for the snake_case mapping).
 */
public final class ChatMessage {

    private String role;
    private String content;

    @SerializedName("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * Identifier of the tool call this message answers, for {@code tool}-role
     * messages. Required by the OpenAI-compatible protocol (Open WebUI); ignored
     * by Ollama, which pairs tool results positionally. Serialized only when set,
     * so Ollama requests are byte-for-byte unchanged.
     */
    @SerializedName("tool_call_id")
    private String toolCallId;

    /** No-arg constructor for Gson. */
    public ChatMessage() {
    }

    private ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** Tool result with no explicit call id (Ollama, or content-parsed calls). */
    public static ChatMessage tool(String content) {
        return tool(content, null);
    }

    /** Tool result paired with the id of the call it answers (OpenAI-compatible). */
    public static ChatMessage tool(String content, String toolCallId) {
        ChatMessage message = new ChatMessage("tool", content);
        message.toolCallId = toolCallId;
        return message;
    }

    /** Assistant message carrying the model's tool calls (built when parsing a backend response). */
    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        ChatMessage message = new ChatMessage("assistant", content);
        message.toolCalls = toolCalls;
        return message;
    }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }

    /** Returns {@code true} when this (assistant) message requested one or more tool calls. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
