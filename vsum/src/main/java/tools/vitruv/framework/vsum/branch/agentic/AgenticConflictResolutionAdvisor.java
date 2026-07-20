package tools.vitruv.framework.vsum.branch.agentic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.agentic.llm.ChatMessage;
import tools.vitruv.framework.vsum.branch.agentic.llm.ContentToolCallParser;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmChatModel;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmException;
import tools.vitruv.framework.vsum.branch.agentic.llm.ToolCall;
import tools.vitruv.framework.vsum.branch.agentic.llm.ToolSpec;
import tools.vitruv.framework.vsum.branch.agentic.tools.AgentTool;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A local, tool-using ("agentic") {@link ConflictResolutionAdvisor}.
 *
 * <p>Instead of a single one-shot prompt, this advisor runs a bounded ReAct
 * loop against a locally hosted model (via {@link LlmChatModel}): the model may
 * call inspection tools (conflict context, resolution history, replay
 * simulation) as many times as it needs within a configured iteration and
 * wall-clock budget, then commits to a decision by calling the terminal
 * {@code submit_resolution} tool. The 2026 merge-conflict literature
 * (Merge-Bench, MergeConflictBench) reports that this tool-using, self-checking
 * pattern is what lets local models exceed the accuracy ceiling of a single LLM
 * call, which is why the loop — rather than a bare prompt — is the core here.
 *
 * <p>The advisor is defensive by construction: a disabled configuration, an
 * unreachable backend, a transport error, a timeout, an exhausted budget, or a
 * malformed decision all resolve to {@link Optional#empty()} ("no opinion"),
 * exactly like {@link ConflictResolutionAdvisor#NONE}. It therefore can only
 * ever <em>add</em> an opinion to the pipeline; it can never block or corrupt a
 * merge. Every run is recorded to an {@link AgenticTraceSink} for explainability.
 */
public final class AgenticConflictResolutionAdvisor implements ConflictResolutionAdvisor {

    private static final Logger LOGGER = LogManager.getLogger(AgenticConflictResolutionAdvisor.class);

    private static final String SUBMIT_TOOL = "submit_resolution";

    private static final String SYSTEM_PROMPT = """
            You are a merge-conflict resolution assistant for the Vitruvius model-driven \
            engineering framework. Two branches changed the SAME feature of the SAME model \
            element, so exactly one side's value can survive.

            Vitruvius conventions you must apply:
            - An ORIGINAL change was made by a human in an editor; a CONSEQUENTIAL change was \
            generated automatically by the consistency engine. Prefer ORIGINAL over CONSEQUENTIAL.
            - When both sides are the same origin, use the recorded resolution history and the \
            structural facts to decide, and be less confident.
            - If you genuinely cannot tell which side is correct, choose "none" so a human decides.

            Work step by step and gather your own evidence before deciding — do NOT rely only on \
            the opening summary and do NOT guess field values. The tools let you pull everything \
            you need yourself: 'get_conflict_context' for the exact machine-readable facts, \
            'get_resolution_history' for how comparable conflicts were resolved before (with the \
            recorded rationales), 'get_element_history' for each branch's recorded change history \
            on this element, and 'simulate_replay' (when available) to test a candidate choice. \
            When ready, you MUST call the 'submit_resolution' tool exactly once with your final \
            decision. Never answer with free text instead of calling 'submit_resolution'.""";

    private final LlmChatModel model;
    private final List<AgentTool> tools;
    private final AgenticAdvisorConfig config;
    private final AgenticTraceSink traceSink;
    private final String sourceId;

    /**
     * Creates an advisor.
     *
     * @param model     the local chat backend, never null.
     * @param tools     the inspection tools exposed to the model (may be empty).
     * @param config    behavioural configuration, never null.
     * @param traceSink where finished traces are sent; use {@link AgenticTraceSink#NONE} for none.
     */
    public AgenticConflictResolutionAdvisor(LlmChatModel model, List<AgentTool> tools,
                                            AgenticAdvisorConfig config, AgenticTraceSink traceSink) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink must not be null");
        this.sourceId = "agentic-llm(" + model.modelId() + ")";
    }

    @Override
    public Optional<ResolutionProposal> propose(UpdateConflict conflict) {
        Objects.requireNonNull(conflict, "conflict must not be null");

        AgenticTrace trace = new AgenticTrace(
                ResolutionHistory.signature(conflict),
                conflict.getElementUuid(),
                conflict.getFeatureName(),
                model.modelId());
        long startNanos = System.nanoTime();

        if (!config.isEnabled()) {
            return finish(trace, AgenticTrace.Outcome.DISABLED, null, null, null, startNanos, Optional.empty());
        }
        if (!model.isAvailable()) {
            LOGGER.info("Agentic advisor backend '{}' is not reachable at {}; giving no opinion",
                    model.modelId(), config.getEndpoint());
            return finish(trace, AgenticTrace.Outcome.ERROR, null, null,
                    "backend unreachable", startNanos, Optional.empty());
        }

        try {
            return runLoop(conflict, trace, startNanos);
        } catch (RuntimeException e) {
            LOGGER.warn("Agentic advisor failed unexpectedly; giving no opinion: {}", e.getMessage());
            return finish(trace, AgenticTrace.Outcome.ERROR, null, null,
                    "unexpected error: " + e.getMessage(), startNanos, Optional.empty());
        }
    }

    private Optional<ResolutionProposal> runLoop(UpdateConflict conflict, AgenticTrace trace, long startNanos) {
        Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }

        List<ToolSpec> toolSpecs = new ArrayList<>();
        for (AgentTool tool : tools) {
            toolSpecs.add(tool.toSpec());
        }
        toolSpecs.add(submitToolSpec());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.add(ChatMessage.user(openingPrompt(conflict)));

        long deadlineNanos = startNanos + config.getDeadlineSeconds() * 1_000_000_000L;
        boolean nudged = false;

        for (int iteration = 1; iteration <= config.getMaxIterations(); iteration++) {
            if (System.nanoTime() > deadlineNanos) {
                return finish(trace, AgenticTrace.Outcome.TIMEOUT, null, null,
                        "deadline exceeded", startNanos, Optional.empty());
            }

            ChatMessage assistant;
            try {
                assistant = model.chat(messages, toolSpecs);
            } catch (LlmException e) {
                LOGGER.info("Agentic advisor chat call failed on iteration {}: {}", iteration, e.getMessage());
                return finish(trace, AgenticTrace.Outcome.ERROR, null, null,
                        e.getMessage(), startNanos, Optional.empty());
            }
            messages.add(assistant);
            AgenticTrace.Step step = trace.addStep(iteration, assistant.getContent());

            List<ToolCall> calls = resolveToolCalls(assistant);
            if (calls.isEmpty()) {
                if (!nudged) {
                    nudged = true;
                    LOGGER.info("[iteration {}] model replied without calling a tool; nudging for a structured call",
                            iteration);
                    messages.add(ChatMessage.user(
                            "You did not call a tool. Reply with ONLY a JSON tool call, one line, for example:\n"
                            + "{\"name\":\"submit_resolution\",\"arguments\":{\"choice\":\"source\","
                            + "\"confidence\":0.9,\"rationale\":\"human original change wins\"}}"));
                    continue;
                }
                Optional<ResolutionProposal> textFallback =
                        parseTextDecision(conflict, assistant.getContent(), trace, startNanos);
                if (textFallback.isPresent()) {
                    return textFallback;
                }
                return finish(trace, AgenticTrace.Outcome.EXHAUSTED, null, null,
                        "model returned no tool call after nudge", startNanos, Optional.empty());
            }

            for (ToolCall call : calls) {
                String toolName = call.name();
                if (SUBMIT_TOOL.equals(toolName)) {
                    LOGGER.info("[iteration {}] model calls {}({})", iteration, SUBMIT_TOOL, call.arguments());
                    step.addToolInvocation(SUBMIT_TOOL, call.arguments().toString(), "decision recorded");
                    return decide(conflict, call.arguments(), trace, startNanos);
                }
                AgentTool tool = toolsByName.get(toolName);
                LOGGER.info("[iteration {}] model calls {}({})", iteration, toolName, call.arguments());
                String result = (tool == null)
                        ? "error: unknown tool '" + toolName + "'"
                        : tool.execute(call.arguments(), conflict);
                LOGGER.info("[iteration {}] {} -> {}", iteration, toolName, truncateForLog(result));
                step.addToolInvocation(toolName, call.arguments().toString(), result);
                // Pair the result to the call id for OpenAI-compatible backends;
                // null for Ollama, where the field is simply omitted.
                messages.add(ChatMessage.tool(result, call.id()));
            }
        }

        return finish(trace, AgenticTrace.Outcome.EXHAUSTED, null, null,
                "iteration budget exhausted", startNanos, Optional.empty());
    }

    /** Native Ollama tool_calls, or JSON tool blobs embedded in content (7B models). */
    private List<ToolCall> resolveToolCalls(ChatMessage assistant) {
        if (assistant.hasToolCalls()) {
            return assistant.getToolCalls();
        }
        return ContentToolCallParser.parse(assistant.getContent());
    }

    /**
     * Last-resort parser for models that write their decision in prose or as inline JSON
     * instead of calling {@code submit_resolution} through the tool channel.
     */
    private Optional<ResolutionProposal> parseTextDecision(UpdateConflict conflict, String content,
                                                            AgenticTrace trace, long startNanos) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (ToolCall call : ContentToolCallParser.parse(content)) {
            if (SUBMIT_TOOL.equals(call.name())) {
                return decide(conflict, call.arguments(), trace, startNanos);
            }
        }
        String lower = content.toLowerCase();

        // Phrases that unambiguously pick one side.
        boolean pickSource = lower.contains("choose source") || lower.contains("keep source")
                || lower.contains("prefer source") || lower.contains("recommend source")
                || lower.contains("select source") || lower.contains("go with source")
                || lower.contains("pick source");
        boolean pickTarget = lower.contains("choose target") || lower.contains("keep target")
                || lower.contains("prefer target") || lower.contains("recommend target")
                || lower.contains("select target") || lower.contains("go with target")
                || lower.contains("pick target");

        if (pickSource == pickTarget) {
            // Both or neither — genuinely ambiguous, do not guess.
            return Optional.empty();
        }

        SemanticChangeEntry chosen = pickSource ? conflict.getSourceEntry() : conflict.getTargetEntry();
        String side = pickSource ? "source" : "target";
        String rationale = "text-parsed (model did not call submit_resolution): "
                + content.substring(0, Math.min(200, content.length())).trim();
        LOGGER.info("Agentic advisor: text fallback detected '{}' from model prose", side);
        ResolutionProposal proposal = new ResolutionProposal(chosen, 0.65, rationale, sourceId + "[text]");
        return finish(trace, AgenticTrace.Outcome.RESOLVED, side, 0.65, rationale, startNanos,
                Optional.of(proposal));
    }

    private Optional<ResolutionProposal> decide(UpdateConflict conflict, JsonObject args,
                                                AgenticTrace trace, long startNanos) {
        String choice;
        double confidence;
        String rationale;
        try {
            choice = args.get("choice").getAsString().trim().toLowerCase();
            confidence = args.get("confidence").getAsDouble();
            rationale = args.has("rationale") && !args.get("rationale").isJsonNull()
                    ? args.get("rationale").getAsString()
                    : "(no rationale provided)";
        } catch (RuntimeException e) {
            LOGGER.info("Agentic advisor produced a malformed decision; giving no opinion: {}", args);
            return finish(trace, AgenticTrace.Outcome.NO_OPINION, null, null,
                    "malformed decision", startNanos, Optional.empty());
        }

        if ("none".equals(choice)) {
            return finish(trace, AgenticTrace.Outcome.NO_OPINION, "none", confidence, rationale,
                    startNanos, Optional.empty());
        }

        SemanticChangeEntry chosen;
        if ("source".equals(choice)) {
            chosen = conflict.getSourceEntry();
        } else if ("target".equals(choice)) {
            chosen = conflict.getTargetEntry();
        } else {
            LOGGER.info("Agentic advisor returned unknown choice '{}'; giving no opinion", choice);
            return finish(trace, AgenticTrace.Outcome.NO_OPINION, choice, confidence, rationale,
                    startNanos, Optional.empty());
        }

        double clamped = Math.max(0.0, Math.min(1.0, confidence));
        LOGGER.info("Decision: keep '{}' (confidence={}) - {}", choice, clamped, rationale);
        ResolutionProposal proposal = new ResolutionProposal(
                chosen, clamped, rationale + " [" + sourceId + "]", sourceId);
        return finish(trace, AgenticTrace.Outcome.RESOLVED, choice, clamped, rationale,
                startNanos, Optional.of(proposal));
    }

    private Optional<ResolutionProposal> finish(AgenticTrace trace, AgenticTrace.Outcome outcome,
                                                String choice, Double confidence, String rationale,
                                                long startNanos, Optional<ResolutionProposal> result) {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        trace.finish(outcome, choice, confidence, rationale, elapsed);
        try {
            traceSink.accept(trace);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to persist agentic trace: {}", e.getMessage());
        }
        return result;
    }

    private String openingPrompt(UpdateConflict conflict) {
        SemanticChangeEntry source = conflict.getSourceEntry();
        SemanticChangeEntry target = conflict.getTargetEntry();
        return "An update-vs-update conflict must be resolved.\n\n"
                + "Element: " + conflict.getEClass() + " (uuid=" + conflict.getElementUuid() + ")\n"
                + "Feature: " + conflict.getFeatureName() + "\n"
                + "Origin permutation: " + conflict.getOriginPermutation()
                + " | Fundamental type: " + conflict.getFundamentalType()
                + " | Severity: " + conflict.getSeverity() + "\n\n"
                + "SOURCE branch '" + conflict.getSourceBranch() + "': "
                + "changeType=" + source.getChangeType() + ", origin=" + source.getOrigin()
                + ", from=" + source.getFrom() + ", to=" + source.getTo() + "\n"
                + "TARGET branch '" + conflict.getTargetBranch() + "': "
                + "changeType=" + target.getChangeType() + ", origin=" + target.getOrigin()
                + ", from=" + target.getFrom() + ", to=" + target.getTo() + "\n\n"
                + "Decide which side to keep. Use the tools to confirm the facts and any relevant "
                + "history, then call 'submit_resolution'.";
    }

    private ToolSpec submitToolSpec() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject choice = new JsonObject();
        choice.addProperty("type", "string");
        JsonArray choiceEnum = new JsonArray();
        choiceEnum.add("source");
        choiceEnum.add("target");
        choiceEnum.add("none");
        choice.add("enum", choiceEnum);
        choice.addProperty("description",
                "Which side to keep: 'source', 'target', or 'none' to defer to a human.");
        properties.add("choice", choice);

        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "number");
        confidence.addProperty("description",
                "Calibrated confidence in the chosen side, from 0.0 to 1.0.");
        properties.add("confidence", confidence);

        JsonObject rationale = new JsonObject();
        rationale.addProperty("type", "string");
        rationale.addProperty("description", "Short explanation of the decision.");
        properties.add("rationale", rationale);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("choice");
        required.add("confidence");
        required.add("rationale");
        schema.add("required", required);

        return new ToolSpec(SUBMIT_TOOL,
                "Commit to a final resolution decision for the current conflict. Call exactly once.",
                schema);
    }

    /** Keeps a single tool result readable on one log line without truncating the full trace file. */
    private static String truncateForLog(String result) {
        if (result == null) {
            return "";
        }
        String oneLine = result.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
