package tools.vitruv.framework.vsum.branch.agentic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.MergeManager;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmChatModel;
import tools.vitruv.framework.vsum.branch.agentic.llm.OllamaClient;
import tools.vitruv.framework.vsum.branch.agentic.llm.OpenAiCompatibleClient;
import tools.vitruv.framework.vsum.branch.agentic.tools.AgentTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.AuditHistoryTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.ConflictContextTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.ElementHistoryTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.ReplaySimulationContext;
import tools.vitruv.framework.vsum.branch.agentic.tools.SimulateReplayTool;
import tools.vitruv.framework.vsum.branch.storage.AuditHistoryAdvisor;
import tools.vitruv.framework.vsum.branch.storage.PersistedResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.ResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the full agentic advisor stack. During a real merge the stack is
 * discovered and installed automatically through {@link AgenticAdvisorProvider}
 * (see the {@code ConflictResolutionAdvisorProvider} SPI); the methods here also
 * remain the wiring entry point for tests and for callers that host a
 * non-default backend.
 *
 * <p>The assembled advisor is a {@link CompositeConflictResolutionAdvisor} that
 * tries the cheap {@link AuditHistoryAdvisor} statistical baseline first and only
 * falls through to the local-LLM {@link AgenticConflictResolutionAdvisor} when
 * the baseline has no opinion. All of its data sources are self-feeding:
 * <ul>
 *   <li>resolution history — parsed from the persisted {@code .vitruvius/audit}
 *       log ({@link PersistedResolutionHistory}),</li>
 *   <li>element change history — parsed from the persisted per-branch
 *       changelogs ({@link ElementHistoryTool}),</li>
 *   <li>conflict facts — taken from the live {@code UpdateConflict}
 *       ({@link ConflictContextTool}).</li>
 * </ul>
 * Configuration is read from
 * {@code <repoRoot>/.vitruvius/config/agentic-advisor.json}; a missing file
 * yields safe defaults. Traces are written to {@code .vitruvius/audit/}.
 */
public final class AgenticAdvisorFactory {

    private static final Logger LOGGER = LogManager.getLogger(AgenticAdvisorFactory.class);

    private AgenticAdvisorFactory() {
    }

    /**
     * Builds the baseline-then-agentic composite advisor for the given
     * repository, with every data source read from the repository's own
     * persisted state. Configuration is loaded from
     * {@code <repoRoot>/.vitruvius/config/agentic-advisor.json} (defaults when
     * absent).
     *
     * @param repoRoot the Git repository root.
     * @return the composite advisor ready to install.
     */
    public static ConflictResolutionAdvisor build(Path repoRoot) {
        return build(repoRoot, loadConfig(repoRoot));
    }

    /**
     * Builds the composite advisor for the given repository with an explicit
     * configuration, using the default {@link OllamaClient} backend, the
     * persisted audit log as resolution history, and file-based trace
     * persistence.
     *
     * @param repoRoot the Git repository root.
     * @param config   behavioural configuration, never null.
     * @return the composite advisor ready to install.
     */
    public static ConflictResolutionAdvisor build(Path repoRoot, AgenticAdvisorConfig config) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");
        return build(repoRoot, new PersistedResolutionHistory(repoRoot), createModel(config),
                config, new FileAgenticTraceSink(repoRoot), null);
    }

    /**
     * Selects the chat backend from the configured provider. For
     * {@link AgenticAdvisorConfig#PROVIDER_OPENAI} the API key is read from the
     * environment variable named by {@link AgenticAdvisorConfig#getApiKeyEnv()} —
     * the key value is never taken from the config file, so the config can be
     * shared/committed while each user supplies their own key. A missing key does
     * not fail construction: the client simply reports itself unavailable and the
     * advisor gives no opinion.
     */
    public static LlmChatModel createModel(AgenticAdvisorConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.isOpenAiProvider()) {
            String envVar = config.getApiKeyEnv();
            String apiKey = System.getenv(envVar);
            if (apiKey == null || apiKey.isBlank()) {
                LOGGER.warn("Provider '{}' selected but environment variable {} is not set; "
                                + "the agentic advisor will stay silent until it is exported with a valid key.",
                        AgenticAdvisorConfig.PROVIDER_OPENAI, envVar);
            } else {
                LOGGER.info("Using OpenAI-compatible backend at {} (model '{}'), key from ${}",
                        config.getEndpoint(), config.getModel(), envVar);
            }
            return new OpenAiCompatibleClient(config, apiKey);
        }
        return new OllamaClient(config);
    }

    /**
     * Builds the baseline-then-agentic composite advisor with an explicit
     * resolution history, using the default {@link OllamaClient} backend and
     * file-based trace persistence. Prefer {@link #build(Path)} unless the
     * history must come from somewhere other than the persisted audit log.
     *
     * @param repoRoot the Git repository root.
     * @param history  the resolution history backing both the baseline advisor and
     *                 the agent's history tool.
     * @return the composite advisor ready to install.
     */
    public static ConflictResolutionAdvisor build(Path repoRoot, ResolutionHistory history) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(history, "history must not be null");

        AgenticAdvisorConfig config = loadConfig(repoRoot);
        return build(repoRoot, history, createModel(config), config,
                new FileAgenticTraceSink(repoRoot), null);
    }

    /**
     * Builds the composite advisor with an explicit backend, history,
     * configuration, trace sink and (optional) replay-simulation context.
     * Primarily for tests and for callers that host a non-default local model or
     * can supply a live merge context for the {@code simulate_replay}
     * verification tool.
     *
     * @param replayContext the sandboxed replay backend for the verification tool,
     *                      or {@code null} to omit that tool (e.g. when no Git merge
     *                      context is available).
     */
    public static ConflictResolutionAdvisor build(Path repoRoot, ResolutionHistory history,
                                                  LlmChatModel model, AgenticAdvisorConfig config,
                                                  AgenticTraceSink traceSink,
                                                  ReplaySimulationContext replayContext) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(history, "history must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(traceSink, "traceSink must not be null");

        List<AgentTool> agentTools = new ArrayList<>();
        agentTools.add(new ConflictContextTool());
        agentTools.add(new AuditHistoryTool(history));
        agentTools.add(new ElementHistoryTool(new SemanticChangelogManager(repoRoot)));
        if (replayContext != null) {
            agentTools.add(new SimulateReplayTool(replayContext));
        }

        ConflictResolutionAdvisor baseline = new AuditHistoryAdvisor(history);
        ConflictResolutionAdvisor agentic =
                new AgenticConflictResolutionAdvisor(model, agentTools, config, traceSink);
        return CompositeConflictResolutionAdvisor.baselineThenAgentic(baseline, agentic);
    }

    /**
     * Convenience: builds the fully self-feeding composite advisor and installs
     * it on the given {@link MergeManager} in one call. Only needed when a
     * repository has not opted in via the config file (in which case
     * {@link AgenticAdvisorProvider} installs the stack automatically at
     * {@code MergeManager} construction).
     */
    public static void installOn(MergeManager mergeManager, Path repoRoot) {
        Objects.requireNonNull(mergeManager, "mergeManager must not be null");
        mergeManager.setResolutionAdvisor(build(repoRoot));
        LOGGER.info("Agentic conflict-resolution advisor installed on MergeManager for {}", repoRoot);
    }

    private static AgenticAdvisorConfig loadConfig(Path repoRoot) {
        try {
            Path configDir = repoRoot.resolve(".vitruvius").resolve("config");
            return AgenticAdvisorConfig.load(configDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to load agentic advisor config, using defaults: {}", e.getMessage());
            return AgenticAdvisorConfig.defaults();
        }
    }
}
