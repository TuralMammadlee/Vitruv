package tools.vitruv.framework.vsum.branch.agentic.llm;

import java.util.List;

/**
 * Minimal chat abstraction over a tool-calling LLM.
 *
 * <p>Kept deliberately small so it can be implemented by {@link OllamaClient}
 * for production and by a trivial stub in tests, without either the agent loop
 * or the test suite depending on HTTP details. All backends are expected to run
 * locally; the interface intentionally exposes no notion of remote credentials.
 */
public interface LlmChatModel {

    /**
     * Sends the conversation so far, optionally with a set of callable tools,
     * and returns the model's next (assistant) message.
     *
     * @param messages the ordered conversation, never null or empty.
     * @param tools    the tools the model may call; may be empty but not null.
     * @return the assistant message, which may contain tool calls.
     * @throws LlmException on any transport, timeout, or protocol error.
     */
    ChatMessage chat(List<ChatMessage> messages, List<ToolSpec> tools) throws LlmException;

    /**
     * Cheap liveness probe. Returns {@code true} if the backend is reachable and
     * ready to serve chat requests. Implementations must not throw.
     */
    boolean isAvailable();

    /** Human-readable identifier of the underlying model (for the audit trail). */
    String modelId();
}
