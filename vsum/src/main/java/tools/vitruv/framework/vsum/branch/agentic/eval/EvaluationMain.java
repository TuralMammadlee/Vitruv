package tools.vitruv.framework.vsum.branch.agentic.eval;

import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorConfig;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorFactory;
import tools.vitruv.framework.vsum.branch.agentic.AgenticConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.agentic.AgenticTraceSink;
import tools.vitruv.framework.vsum.branch.agentic.FileAgenticTraceSink;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmChatModel;
import tools.vitruv.framework.vsum.branch.agentic.tools.AgentTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.AuditHistoryTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.ConflictContextTool;
import tools.vitruv.framework.vsum.branch.storage.AuditHistoryAdvisor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point that scores the statistical baseline and (when the
 * configured backend — local Ollama or an OpenAI-compatible hosted server — is
 * reachable) the agentic advisor over the built-in {@link ScenarioLibrary},
 * prints a summary, and writes per-advisor JSON and CSV reports.
 *
 * <p>Usage: {@code java ... EvaluationMain [outputDir [configDir]]}
 * <ul>
 *   <li>{@code outputDir} — where to write reports (default: {@code ./agentic-eval-reports})</li>
 *   <li>{@code configDir} — path to the {@code .vitruvius/config} directory whose
 *       {@code agentic-advisor.json} should be used (default: looks for
 *       {@code .vitruvius/config} in the current working directory)</li>
 * </ul>
 */
public final class EvaluationMain {

    private EvaluationMain() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "agentic-eval-reports");
        Files.createDirectories(outputDir);

        // Load config: explicit configDir arg, or auto-detect .vitruvius/config
        // in the current working directory (where Maven runs the exec goal from).
        Path configDir;
        if (args.length > 1) {
            configDir = Path.of(args[1]);
        } else {
            configDir = Path.of(".vitruvius", "config");
        }
        AgenticAdvisorConfig config = AgenticAdvisorConfig.load(configDir);
        System.out.println("Using config: model=" + config.getModel()
                + ", endpoint=" + config.getEndpoint()
                + "  (loaded from " + configDir.toAbsolutePath() + ")");

        ScenarioLibrary library = ScenarioLibrary.create();
        List<EvalScenario> scenarios = library.scenarios();
        System.out.println("Evaluating " + scenarios.size() + " scenarios.");

        // Baseline: statistical audit-history advisor (offline, deterministic).
        ConflictResolutionAdvisor baseline = new AuditHistoryAdvisor(library.history());
        EvaluationReport baselineReport = AdvisorEvaluator.evaluate("audit-history", baseline, scenarios);
        System.out.println(baselineReport.summaryLine());
        write(outputDir, "audit-history", baselineReport);

        // Agentic advisor: only if the configured backend is reachable. The
        // backend (Ollama vs. an OpenAI-compatible hosted server) is chosen from
        // config.getProvider(), exactly as AgenticAdvisorProvider chooses it for a
        // real merge, so this harness exercises the same wiring a live run would use.
        LlmChatModel client = AgenticAdvisorFactory.createModel(config);
        if (!client.isAvailable()) {
            System.out.println("Backend '" + config.getProvider() + "' not reachable at "
                    + config.getEndpoint() + "; skipping agentic evaluation.");
            if (config.isOpenAiProvider()) {
                System.out.println("-> Check that " + config.getApiKeyEnv()
                        + " is exported with a valid API key and the endpoint is correct.");
            } else {
                System.out.println("-> Make sure 'ollama serve' is running and '"
                        + config.getModel() + "' is pulled.");
            }
            return;
        }
        System.out.println("Backend '" + config.getProvider() + "' is up, model="
                + config.getModel() + ". Running agentic eval...");

        // The evaluation scenarios are self-contained (no persisted changelogs on
        // disk), so only the two changelog-independent tools are exercised here;
        // ElementHistoryTool is covered by the live merge wiring in AgenticAdvisorFactory.
        List<AgentTool> tools = List.of(
                new ConflictContextTool(),
                new AuditHistoryTool(library.history()));
        // Persist every run's full transcript (tool calls, rationale, decision) so the
        // agent's reasoning can be inspected under <outputDir>/.vitruvius/audit/*.agentic-trace.json.
        AgenticTraceSink traceSink = new FileAgenticTraceSink(outputDir);
        ConflictResolutionAdvisor agentic =
                new AgenticConflictResolutionAdvisor(client, tools, config, traceSink);
        EvaluationReport agenticReport = AdvisorEvaluator.evaluate(
                "agentic-llm(" + config.getModel() + ")", agentic, scenarios);
        System.out.println(agenticReport.summaryLine());
        write(outputDir, "agentic-llm", agenticReport);
        System.out.println("Per-conflict reasoning transcripts written to "
                + outputDir.resolve(".vitruvius").resolve("audit").toAbsolutePath());
    }

    private static void write(Path outputDir, String prefix, EvaluationReport report) throws IOException {
        Files.writeString(outputDir.resolve(prefix + "-report.json"), report.toJson(), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve(prefix + "-report.csv"), report.toCsv(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + prefix + "-report.json / .csv to " + outputDir.toAbsolutePath());
    }
}
