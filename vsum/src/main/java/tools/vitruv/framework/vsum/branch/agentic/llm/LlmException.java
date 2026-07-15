package tools.vitruv.framework.vsum.branch.agentic.llm;

/**
 * Thrown when a chat request to the local LLM fails (transport error, timeout,
 * non-200 response, or an error field in the response body).
 *
 * <p>The agentic advisor treats any {@code LlmException} as "no opinion" and
 * degrades gracefully to manual resolution; it never propagates out of the
 * merge pipeline.
 */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
