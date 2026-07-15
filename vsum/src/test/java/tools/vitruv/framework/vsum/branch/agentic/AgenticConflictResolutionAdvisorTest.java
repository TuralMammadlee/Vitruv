package tools.vitruv.framework.vsum.branch.agentic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.agentic.tools.AuditHistoryTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.ConflictContextTool;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.InMemoryResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the agentic ReAct loop in {@link AgenticConflictResolutionAdvisor},
 * driven by a scripted {@link StubChatModel} so no live backend is required.
 */
class AgenticConflictResolutionAdvisorTest {

    private static SemanticChangeEntry entry(ChangeOrigin origin, String to) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValued")
                .elementUuid("uuid-1")
                .eClass("entities::Entity")
                .feature("name")
                .from("old")
                .to(to)
                .origin(origin)
                .build();
    }

    private static UpdateConflict conflict() {
        return new UpdateConflict("uuid-1", "entities::Entity", "name", "feature", "main",
                entry(ChangeOrigin.ORIGINAL, "srcValue"), entry(ChangeOrigin.CONSEQUENTIAL, "tgtValue"));
    }

    private AgenticConflictResolutionAdvisor advisor(StubChatModel model, AgenticTraceSink sink) {
        return new AgenticConflictResolutionAdvisor(
                model,
                List.of(new ConflictContextTool(), new AuditHistoryTool(new InMemoryResolutionHistory())),
                AgenticAdvisorConfig.defaults(),
                sink);
    }

    @Test
    @DisplayName("proposes the source side when the model submits choice=source")
    void submitsSource() {
        UpdateConflict c = conflict();
        StubChatModel model = new StubChatModel().enqueueToolCall("submit_resolution",
                "{\"choice\":\"source\",\"confidence\":0.9,\"rationale\":\"human change\"}");

        Optional<ResolutionProposal> proposal = advisor(model, AgenticTraceSink.NONE).propose(c);

        assertTrue(proposal.isPresent());
        assertSame(c.getSourceEntry(), proposal.get().chosenEntry());
        assertEquals(0.9, proposal.get().confidence(), 1e-9);
        assertTrue(proposal.get().source().startsWith("agentic-llm("));
    }

    @Test
    @DisplayName("runs an inspection tool, then submits the target side")
    void toolCallThenSubmit() {
        UpdateConflict c = conflict();
        StubChatModel model = new StubChatModel()
                .enqueueToolCall("get_conflict_context", "{}")
                .enqueueToolCall("submit_resolution",
                        "{\"choice\":\"target\",\"confidence\":0.75,\"rationale\":\"convention\"}");

        Optional<ResolutionProposal> proposal = advisor(model, AgenticTraceSink.NONE).propose(c);

        assertTrue(proposal.isPresent());
        assertSame(c.getTargetEntry(), proposal.get().chosenEntry());
        assertEquals(2, model.chatCalls(), "should have made two model turns");
    }

    @Test
    @DisplayName("gives no opinion when the model chooses 'none'")
    void choosesNone() {
        StubChatModel model = new StubChatModel().enqueueToolCall("submit_resolution",
                "{\"choice\":\"none\",\"confidence\":0.2,\"rationale\":\"ambiguous\"}");
        assertTrue(advisor(model, AgenticTraceSink.NONE).propose(conflict()).isEmpty());
    }

    @Test
    @DisplayName("gives no opinion on a malformed decision (missing confidence)")
    void malformedDecision() {
        StubChatModel model = new StubChatModel().enqueueToolCall("submit_resolution",
                "{\"choice\":\"source\",\"rationale\":\"oops\"}");
        assertTrue(advisor(model, AgenticTraceSink.NONE).propose(conflict()).isEmpty());
    }

    @Test
    @DisplayName("clamps an out-of-range confidence into [0,1]")
    void clampsConfidence() {
        StubChatModel model = new StubChatModel().enqueueToolCall("submit_resolution",
                "{\"choice\":\"source\",\"confidence\":1.7,\"rationale\":\"overconfident\"}");
        ResolutionProposal proposal = advisor(model, AgenticTraceSink.NONE).propose(conflict()).orElseThrow();
        assertEquals(1.0, proposal.confidence(), 1e-9);
    }

    @Test
    @DisplayName("degrades to no opinion when the backend is unavailable")
    void unavailableBackend() {
        StubChatModel model = new StubChatModel().setAvailable(false);
        assertTrue(advisor(model, AgenticTraceSink.NONE).propose(conflict()).isEmpty());
    }

    @Test
    @DisplayName("degrades to no opinion when disabled by configuration")
    void disabled() {
        StubChatModel model = new StubChatModel().enqueueToolCall("submit_resolution",
                "{\"choice\":\"source\",\"confidence\":0.9,\"rationale\":\"x\"}");
        AgenticConflictResolutionAdvisor advisor = new AgenticConflictResolutionAdvisor(
                model, List.of(), AgenticAdvisorConfig.defaults().setEnabled(false), AgenticTraceSink.NONE);
        assertTrue(advisor.propose(conflict()).isEmpty());
        assertEquals(0, model.chatCalls(), "disabled advisor must not call the model");
    }

    @Test
    @DisplayName("gives no opinion when the model never calls a tool")
    void exhaustsWithoutToolCall() {
        StubChatModel model = new StubChatModel()
                .enqueueText("I think source is better.")
                .enqueueText("Still source.");
        assertTrue(advisor(model, AgenticTraceSink.NONE).propose(conflict()).isEmpty());
    }

    @Test
    @DisplayName("parses tool calls written as JSON text in content (7B model behaviour)")
    void contentEmbeddedToolCalls() {
        UpdateConflict c = conflict();
        StubChatModel model = new StubChatModel()
                .enqueueText("{\"name\": \"get_conflict_context\", \"arguments\": {}}")
                .enqueueText("{\"name\": \"submit_resolution\", \"arguments\": "
                        + "{\"choice\":\"source\",\"confidence\":0.9,\"rationale\":\"human original wins\"}}");

        Optional<ResolutionProposal> proposal = advisor(model, AgenticTraceSink.NONE).propose(c);

        assertTrue(proposal.isPresent());
        assertSame(c.getSourceEntry(), proposal.get().chosenEntry());
        assertEquals(2, model.chatCalls());
    }

    @Test
    @DisplayName("records a trace with the final outcome")
    void recordsTrace() {
        AtomicReference<AgenticTrace> captured = new AtomicReference<>();
        StubChatModel model = new StubChatModel().enqueueToolCall("submit_resolution",
                "{\"choice\":\"source\",\"confidence\":0.9,\"rationale\":\"human change\"}");

        advisor(model, captured::set).propose(conflict());

        AgenticTrace trace = captured.get();
        assertNotNull(trace);
        assertEquals(AgenticTrace.Outcome.RESOLVED, trace.getOutcome());
        assertEquals("source", trace.getChosenSide());
        assertFalse(trace.getSteps().isEmpty());
    }
}
