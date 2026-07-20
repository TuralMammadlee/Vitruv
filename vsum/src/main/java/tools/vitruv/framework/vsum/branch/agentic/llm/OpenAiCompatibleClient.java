package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link LlmChatModel} backed by an OpenAI-compatible chat-completions API, such
 * as a self-hosted <a href="https://openwebui.com">Open WebUI</a> deployment.
 *
 * <p>This is the seam for using a university- or team-hosted model instead of a
 * local Ollama: the endpoint, model, and (per-user) API key are configured, and
 * everything downstream — the tool-using ReAct loop, the inspection tools, the
 * trace log — is identical to the Ollama path. Only the wire format differs, and
 * that difference is confined to this class:
 * <ul>
 *   <li><b>Auth</b>: an {@code Authorization: Bearer &lt;key&gt;} header. The key is
 *       supplied by the caller (resolved from an environment variable, never from
 *       committed config), so it is never logged, serialized, or written to disk.</li>
 *   <li><b>Tool-call arguments</b>: OpenAI returns them as a JSON <em>string</em>;
 *       this client parses them back into a {@link JsonObject} so the rest of the
 *       code sees the same shape as the Ollama path.</li>
 *   <li><b>Tool results</b>: each is paired to its call via {@code tool_call_id}.</li>
 * </ul>
 *
 * <p>Uses only the JDK's {@link HttpClient} and Gson, so no new dependency is
 * introduced. Requests are non-streaming.
 */
public final class OpenAiCompatibleClient implements LlmChatModel {

    private static final Logger LOGGER = LogManager.getLogger(OpenAiCompatibleClient.class);

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final Duration requestTimeout;
    private final double temperature;

    /**
     * @param config the agentic configuration (endpoint, model, temperature, timeout).
     * @param apiKey the bearer token for the hosted API; may be blank, in which
     *               case {@link #isAvailable()} reports unavailable rather than
     *               sending an unauthenticated request.
     */
    public OpenAiCompatibleClient(AgenticAdvisorConfig config, String apiKey) {
        Objects.requireNonNull(config, "config must not be null");
        this.endpoint = stripTrailingSlash(config.getEndpoint());
        this.model = config.getModel();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
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
        if (apiKey.isEmpty()) {
            throw new LlmException("no API key configured for OpenAI-compatible endpoint " + endpoint);
        }

        String body = gson.toJson(buildRequestBody(messages, tools));
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("Failed to reach OpenAI-compatible endpoint " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for response", e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new LlmException("Authentication rejected (HTTP " + response.statusCode()
                    + ") by " + endpoint + "; check the API key");
        }
        if (response.statusCode() != 200) {
            throw new LlmException("Endpoint returned HTTP " + response.statusCode()
                    + " for model '" + model + "': " + truncate(response.body()));
        }
        return parseAssistantMessage(response.body());
    }

    /**
     * Translates the internal conversation into an OpenAI chat-completions request
     * body. Package-visible for unit testing the wire mapping without a server.
     */
    JsonObject buildRequestBody(List<ChatMessage> messages, List<ToolSpec> tools) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("stream", false);
        request.addProperty("temperature", temperature);

        JsonArray messageArray = new JsonArray();
        for (ChatMessage message : messages) {
            messageArray.add(toOpenAiMessage(message));
        }
        request.add("messages", messageArray);

        if (tools != null && !tools.isEmpty()) {
            // ToolSpec already serializes to the OpenAI {type,function{name,description,parameters}} shape.
            request.add("tools", gson.toJsonTree(tools));
        }
        return request;
    }

    private JsonObject toOpenAiMessage(ChatMessage message) {
        JsonObject json = new JsonObject();
        json.addProperty("role", message.getRole());
        json.addProperty("content", message.getContent() == null ? "" : message.getContent());
        if (message.getToolCallId() != null) {
            json.addProperty("tool_call_id", message.getToolCallId());
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (ToolCall call : message.getToolCalls()) {
                JsonObject callJson = new JsonObject();
                if (call.id() != null) {
                    callJson.addProperty("id", call.id());
                }
                callJson.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                // OpenAI expects arguments as a JSON-encoded string, not an object.
                function.addProperty("arguments", gson.toJson(call.arguments()));
                callJson.add("function", function);
                toolCalls.add(callJson);
            }
            json.add("tool_calls", toolCalls);
        }
        return json;
    }

    /**
     * Parses an OpenAI chat-completions response into the internal assistant
     * message, converting each tool call's string arguments back into a
     * {@link JsonObject}. Package-visible for unit testing.
     */
    ChatMessage parseAssistantMessage(String responseBody) throws LlmException {
        JsonObject message = extractMessage(responseBody);

        String content = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString()
                : "";

        if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
            return ChatMessage.assistant(content, null);
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : message.getAsJsonArray("tool_calls")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject callJson = element.getAsJsonObject();
            JsonObject function = callJson.has("function") && callJson.get("function").isJsonObject()
                    ? callJson.getAsJsonObject("function")
                    : null;
            if (function == null || !function.has("name")) {
                continue;
            }
            String id = callJson.has("id") && callJson.get("id").isJsonPrimitive()
                    ? callJson.get("id").getAsString()
                    : null;
            String name = function.get("name").getAsString();
            calls.add(ToolCall.of(id, name, parseArguments(function)));
        }
        return ChatMessage.assistant(content, calls);
    }

    private JsonObject extractMessage(String responseBody) throws LlmException {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new LlmException("Malformed JSON from endpoint: " + truncate(responseBody), e);
        }
        if (root.has("error") && !root.get("error").isJsonNull()) {
            throw new LlmException("Endpoint error: " + root.get("error"));
        }
        if (!root.has("choices") || !root.get("choices").isJsonArray()
                || root.getAsJsonArray("choices").isEmpty()) {
            throw new LlmException("Response contained no choices: " + truncate(responseBody));
        }
        JsonObject firstChoice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!firstChoice.has("message") || !firstChoice.get("message").isJsonObject()) {
            throw new LlmException("Response choice contained no message: " + truncate(responseBody));
        }
        return firstChoice.getAsJsonObject("message");
    }

    /** OpenAI encodes arguments as a JSON string; tolerate an object too, for lenient servers. */
    private static JsonObject parseArguments(JsonObject function) {
        if (!function.has("arguments") || function.get("arguments").isJsonNull()) {
            return new JsonObject();
        }
        JsonElement args = function.get("arguments");
        try {
            if (args.isJsonObject()) {
                return args.getAsJsonObject();
            }
            String raw = args.getAsString();
            if (raw == null || raw.isBlank()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return new JsonObject();
        }
    }

    @Override
    public boolean isAvailable() {
        if (apiKey.isEmpty()) {
            LOGGER.info("OpenAI-compatible endpoint {} has no API key configured; treating as unavailable", endpoint);
            return false;
        }
        HttpRequest ping = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/models"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(ping, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            LOGGER.debug("Endpoint not reachable at {}: {}", endpoint, e.getMessage());
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
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
