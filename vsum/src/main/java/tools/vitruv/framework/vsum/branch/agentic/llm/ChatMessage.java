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

    public static ChatMessage tool(String content) {
        return new ChatMessage("tool", content);
    }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }

    /** Returns {@code true} when this (assistant) message requested one or more tool calls. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
