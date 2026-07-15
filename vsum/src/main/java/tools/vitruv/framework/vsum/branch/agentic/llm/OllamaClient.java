package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@link LlmChatModel} backed by a locally hosted Ollama server via its
 * {@code /api/chat} endpoint. Uses only the JDK's built-in {@link HttpClient}
 * (available since Java 11) and Gson (already a project dependency), so no new
 * Maven dependency is introduced and no code leaves the machine.
 *
 * <p>Requests are non-streaming: the loop needs the complete assistant message
 * (including any {@code tool_calls}) before it can act, and merge conflicts are
 * small enough that streaming buys nothing. Temperature is taken from the
 * supplied {@link AgenticAdvisorConfig} (default {@code 0.0}) to keep decisions
 * as reproducible as the model allows.
 */
public final class OllamaClient implements LlmChatModel {

    private static final Logger LOGGER = LogManager.getLogger(OllamaClient.class);

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final String endpoint;
    private final String model;
    private final Duration requestTimeout;
    private final double temperature;

    public OllamaClient(AgenticAdvisorConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.endpoint = stripTrailingSlash(config.getEndpoint());
        this.model = config.getModel();
        this.temperature = config.getTemperature();
        this.requestTimeout = Duration.ofSeconds(config.getRequestTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, config.getRequestTimeoutSeconds())))
                .build();
    }

    @Override
    public ChatMessage chat(List<ChatMessage> messages, List<ToolSpec> tools) throws LlmException {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new LlmException("messages must not be empty");
        }

        ChatRequest request = new ChatRequest(model, messages,
                (tools == null || tools.isEmpty()) ? null : tools, temperature);
        String body = gson.toJson(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/chat"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("Failed to reach Ollama at " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for Ollama response", e);
        }

        if (response.statusCode() != 200) {
            throw new LlmException("Ollama returned HTTP " + response.statusCode()
                    + " for model '" + model + "': " + truncate(response.body()));
        }

        ChatResponse parsed;
        try {
            parsed = gson.fromJson(response.body(), ChatResponse.class);
        } catch (JsonSyntaxException e) {
            throw new LlmException("Malformed JSON from Ollama: " + truncate(response.body()), e);
        }
        if (parsed == null) {
            throw new LlmException("Empty response body from Ollama");
        }
        if (parsed.error != null && !parsed.error.isBlank()) {
            throw new LlmException("Ollama error: " + parsed.error);
        }
        if (parsed.message == null) {
            throw new LlmException("Ollama response contained no message");
        }
        return parsed.message;
    }

    @Override
    public boolean isAvailable() {
        HttpRequest ping = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(ping, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            LOGGER.debug("Ollama not reachable at {}: {}", endpoint, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public String modelId() {
        return model;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    /** Wire shape of an Ollama {@code /api/chat} request body. */
    private static final class ChatRequest {
        private final String model;
        private final List<ChatMessage> messages;
        private final List<ToolSpec> tools;
        private final boolean stream = false;
        private final Options options;

        ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools, double temperature) {
            this.model = model;
            this.messages = messages;
            this.tools = tools;
            this.options = new Options(temperature);
        }

        private static final class Options {
            private final double temperature;

            Options(double temperature) {
                this.temperature = temperature;
            }
        }
    }

    /** Wire shape of an Ollama {@code /api/chat} response body (fields we use). */
    private static final class ChatResponse {
        private ChatMessage message;
        private String error;
    }
}
