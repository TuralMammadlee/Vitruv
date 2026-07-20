package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the wire mapping in {@link OpenAiCompatibleClient} — exercised
 * without any live server by testing request building and response parsing
 * directly. Covers the two OpenAI-vs-Ollama differences: tool-call arguments are
 * a JSON string, and tool results are paired by {@code tool_call_id}.
 */
class OpenAiCompatibleClientTest {

    private static OpenAiCompatibleClient client(String apiKey) {
        AgenticAdvisorConfig config = AgenticAdvisorConfig.defaults()
                .setProvider("openai")
                .setEndpoint("https://open-webui.example.edu/api")
                .setModel("llama3.1:70b")
                .setApiKeyEnv("IGNORED_IN_TEST");
        return new OpenAiCompatibleClient(config, apiKey);
    }

    @Test
    @DisplayName("request body carries model, messages, and OpenAI-shaped tools")
    void buildsRequestBody() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("resolve this"));
        List<ToolSpec> tools = List.of(new ToolSpec("get_conflict_context", "facts", schema));

        JsonObject body = client("k").buildRequestBody(messages, tools);

        assertEquals("llama3.1:70b", body.get("model").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals(2, body.getAsJsonArray("messages").size());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        // ToolSpec already serializes to {type:function, function:{name,description,parameters}}.
        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("get_conflict_context", tool.getAsJsonObject("function").get("name").getAsString());
    }

    @Test
    @DisplayName("assistant tool_calls are serialized with string arguments and a tool_call_id is preserved")
    void serializesToolCallsAndToolResults() {
        JsonObject args = new JsonObject();
        args.addProperty("branch", "source");
        ChatMessage assistant = ChatMessage.assistant("", List.of(ToolCall.of("call_1", "get_element_history", args)));
        ChatMessage toolResult = ChatMessage.tool("history json here", "call_1");

        JsonObject body = client("k").buildRequestBody(List.of(assistant, toolResult), List.of());
        JsonObject sentAssistant = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        JsonObject sentToolCall = sentAssistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();

        assertEquals("call_1", sentToolCall.get("id").getAsString());
        assertEquals("function", sentToolCall.get("type").getAsString());
        // OpenAI requires arguments as a JSON string, not an object.
        String argString = sentToolCall.getAsJsonObject("function").get("arguments").getAsString();
        assertTrue(argString.contains("\"branch\""));

        JsonObject sentToolResult = body.getAsJsonArray("messages").get(1).getAsJsonObject();
        assertEquals("tool", sentToolResult.get("role").getAsString());
        assertEquals("call_1", sentToolResult.get("tool_call_id").getAsString());
    }

    @Test
    @DisplayName("parses an OpenAI response, decoding string tool-call arguments into an object")
    void parsesResponseWithStringArguments() throws LlmException {
        String response = """
                { "choices": [ { "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [ { "id": "call_9", "type": "function",
                        "function": { "name": "submit_resolution",
                            "arguments": "{\\"choice\\":\\"source\\",\\"confidence\\":0.9,\\"rationale\\":\\"human wins\\"}" } } ]
                } } ] }
                """;

        ChatMessage message = client("k").parseAssistantMessage(response);

        assertTrue(message.hasToolCalls());
        ToolCall call = message.getToolCalls().get(0);
        assertEquals("call_9", call.id());
        assertEquals("submit_resolution", call.name());
        // The string arguments must be decoded back into a usable JSON object.
        assertEquals("source", call.arguments().get("choice").getAsString());
        assertEquals(0.9, call.arguments().get("confidence").getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("parses a plain text response with no tool calls")
    void parsesPlainTextResponse() throws LlmException {
        String response = """
                { "choices": [ { "message": { "role": "assistant", "content": "I recommend source." } } ] }
                """;
        ChatMessage message = client("k").parseAssistantMessage(response);
        assertFalse(message.hasToolCalls());
        assertEquals("I recommend source.", message.getContent());
    }

    @Test
    @DisplayName("surfaces an error object and malformed bodies as LlmException")
    void rejectsErrorAndMalformed() {
        assertThrows(LlmException.class,
                () -> client("k").parseAssistantMessage("{ \"error\": { \"message\": \"bad model\" } }"));
        assertThrows(LlmException.class,
                () -> client("k").parseAssistantMessage("not json"));
        assertThrows(LlmException.class,
                () -> client("k").parseAssistantMessage("{ \"choices\": [] }"));
    }

    @Test
    @DisplayName("no API key -> unavailable, and chat refuses rather than sending unauthenticated")
    void missingKeyIsUnavailable() {
        OpenAiCompatibleClient noKey = client("   ");
        assertFalse(noKey.isAvailable());
        assertThrows(LlmException.class,
                () -> noKey.chat(List.of(ChatMessage.user("hi")), List.of()));
        assertNotNull(noKey.modelId());
    }
}
