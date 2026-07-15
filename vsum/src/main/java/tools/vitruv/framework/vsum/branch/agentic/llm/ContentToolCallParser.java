package tools.vitruv.framework.vsum.branch.agentic.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool invocations that smaller local models embed as JSON text inside the
 * assistant {@code content} field instead of Ollama's native {@code tool_calls} array.
 *
 * <p>Example (observed from {@code qwen2.5-coder:7b}):
 * <pre>
 *   {"name": "get_conflict_context", "arguments": {}}
 *   {"name": "submit_resolution", "arguments": {"choice":"source","confidence":0.9,"rationale":"..."}}
 * </pre>
 */
public final class ContentToolCallParser {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");

    private ContentToolCallParser() {
    }

    /**
     * Extracts zero or more tool calls from free-form assistant text.
     * Returns an empty list when nothing parseable is found.
     */
    public static List<ToolCall> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        Matcher matcher = JSON_OBJECT.matcher(content);
        while (matcher.find()) {
            String candidate = matcher.group();
            try {
                JsonElement element = JsonParser.parseString(candidate);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                if (!obj.has("name") || !obj.get("name").isJsonPrimitive()) {
                    continue;
                }
                String name = obj.get("name").getAsString().trim();
                if (name.isEmpty()) {
                    continue;
                }
                JsonObject args = new JsonObject();
                if (obj.has("arguments") && obj.get("arguments").isJsonObject()) {
                    args = obj.getAsJsonObject("arguments");
                }
                calls.add(ToolCall.of(name, args));
            } catch (JsonSyntaxException | IllegalStateException ignored) {
                // skip malformed fragments
            }
        }
        return calls;
    }
}
