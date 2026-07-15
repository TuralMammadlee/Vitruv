package tools.vitruv.framework.vsum.branch.agentic.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentToolCallParserTest {

    @Test
    void parsesToolCallsEmbeddedInContent() {
        String content = """
                {"name": "get_conflict_context", "arguments": {}}

                {"name": "get_resolution_history", "arguments": {}}
                """;
        List<ToolCall> calls = ContentToolCallParser.parse(content);
        assertEquals(2, calls.size());
        assertEquals("get_conflict_context", calls.get(0).name());
        assertEquals("get_resolution_history", calls.get(1).name());
    }

    @Test
    void parsesSubmitResolutionWithArguments() {
        String content = """
                Some prose here.
                {"name": "submit_resolution", "arguments": {"choice":"source","confidence":0.88,"rationale":"original wins"}}
                """;
        List<ToolCall> calls = ContentToolCallParser.parse(content);
        assertEquals(1, calls.size());
        assertEquals("submit_resolution", calls.get(0).name());
        assertEquals("source", calls.get(0).arguments().get("choice").getAsString());
        assertEquals(0.88, calls.get(0).arguments().get("confidence").getAsDouble(), 1e-9);
    }

    @Test
    void returnsEmptyForPlainText() {
        assertTrue(ContentToolCallParser.parse("I think we should keep the source side.").isEmpty());
    }
}
