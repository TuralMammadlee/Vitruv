package tools.vitruv.framework.vsum.branch.agentic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import tools.vitruv.framework.vsum.branch.agentic.llm.ChatMessage;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmChatModel;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmException;
import tools.vitruv.framework.vsum.branch.agentic.llm.ToolSpec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Test double for {@link LlmChatModel} that replays a scripted sequence of
 * assistant messages, one per {@code chat(...)} call. Messages are built from
 * JSON with Gson, exactly as the real wire responses are parsed, so the stub
 * exercises the same deserialization path as production.
 */
final class StubChatModel implements LlmChatModel {

    private static final Gson GSON = new Gson();

    private final Deque<ChatMessage> scripted = new ArrayDeque<>();
    private boolean available = true;
    private int chatCalls = 0;

    /** Enqueues an assistant message that calls the given tool with the given JSON arguments. */
    StubChatModel enqueueToolCall(String toolName, String argsJson) {
        String json = "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + "{\"function\":{\"name\":\"" + toolName + "\",\"arguments\":" + argsJson + "}}]}";
        scripted.add(GSON.fromJson(json, ChatMessage.class));
        return this;
    }

    /**
     * Enqueues a plain assistant message with no tool calls. The content is set
     * via {@link JsonObject#addProperty} so it is JSON-escaped correctly even
     * when it itself contains quotes or braces (e.g. a tool call the model wrote
     * as inline JSON text).
     */
    StubChatModel enqueueText(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        scripted.add(GSON.fromJson(message, ChatMessage.class));
        return this;
    }

    StubChatModel setAvailable(boolean available) {
        this.available = available;
        return this;
    }

    int chatCalls() {
        return chatCalls;
    }

    @Override
    public ChatMessage chat(List<ChatMessage> messages, List<ToolSpec> tools) throws LlmException {
        chatCalls++;
        if (scripted.isEmpty()) {
            throw new LlmException("stub exhausted");
        }
        return scripted.poll();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String modelId() {
        return "stub-model";
    }
}
