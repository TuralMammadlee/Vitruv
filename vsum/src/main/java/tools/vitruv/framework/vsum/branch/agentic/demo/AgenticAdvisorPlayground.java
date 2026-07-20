package tools.vitruv.framework.vsum.branch.agentic.demo;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorConfig;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorFactory;
import tools.vitruv.framework.vsum.branch.agentic.AgenticConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.agentic.AgenticTraceSink;
import tools.vitruv.framework.vsum.branch.agentic.llm.LlmChatModel;
import tools.vitruv.framework.vsum.branch.agentic.tools.AgentTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.AuditHistoryTool;
import tools.vitruv.framework.vsum.branch.agentic.tools.ConflictContextTool;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.InMemoryResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Interactive, human-run command line for watching the agentic advisor reason
 * about a conflict live: you type the two competing values, and every tool
 * call the model makes ({@code get_conflict_context}, {@code get_resolution_history},
 * ...) is printed to the console as it happens, followed by the final decision.
 *
 * <p>This is deliberately separate from both the automated test suite and the
 * {@code eval} scoring harness: nothing here is asserted or graded. It exists
 * purely so a developer can point the advisor at whatever hosted or local model
 * they configured (via {@code .vitruvius/config/agentic-advisor.json}) and watch
 * it work, repeatedly, with their own inputs.
 *
 * <h3>Usage</h3>
 * <pre>
 *   cd vsum
 *   mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *   java -cp "target/classes;target/cp.txt-contents" \
 *        tools.vitruv.framework.vsum.branch.agentic.demo.AgenticAdvisorPlayground
 * </pre>
 * (On Windows PowerShell, build the classpath string as
 * {@code "target\classes;" + (Get-Content target\cp.txt -Raw)}.)
 *
 * <p>Optionally pass the {@code .vitruvius/config} directory as the first
 * argument; otherwise it is auto-detected by walking up from the current
 * working directory.
 */
public final class AgenticAdvisorPlayground {

    private AgenticAdvisorPlayground() {
    }

    public static void main(String[] args) throws IOException {
        // Raise the branch package's log level so the advisor's live per-turn
        // logging (added specifically for this playground) reaches the console.
        // Left untouched for every other entry point (tests configure their own
        // log4j2-test.xml; production embedders keep whatever level they set).
        Configurator.setLevel("tools.vitruv.framework.vsum.branch", Level.INFO);

        Path configDir = args.length > 0 ? Path.of(args[0]) : findProjectConfigDir();
        if (configDir == null) {
            System.out.println("No .vitruvius/config/agentic-advisor.json found above the current directory.");
            System.out.println("Pass the config directory explicitly: AgenticAdvisorPlayground <path-to>/.vitruvius/config");
            return;
        }

        AgenticAdvisorConfig config = AgenticAdvisorConfig.load(configDir);
        System.out.println("=== Vitruv Agentic Advisor - Live Playground ===");
        System.out.println("config   : " + configDir.toAbsolutePath());
        System.out.println("provider : " + config.getProvider());
        System.out.println("endpoint : " + config.getEndpoint());
        System.out.println("model    : " + config.getModel());
        if (config.isOpenAiProvider()) {
            System.out.println("apiKeyEnv: " + config.getApiKeyEnv() + " (value never printed)");
        }
        System.out.println();

        LlmChatModel model = AgenticAdvisorFactory.createModel(config);
        System.out.println("Checking backend availability...");
        if (!model.isAvailable()) {
            System.out.println("NOT REACHABLE / NOT AUTHENTICATED.");
            if (config.isOpenAiProvider()) {
                System.out.println("-> Check that " + config.getApiKeyEnv() + " is exported in THIS shell "
                        + "and that the endpoint/model in " + configDir + " are correct.");
            } else {
                System.out.println("-> Check that 'ollama serve' is running and the model is pulled.");
            }
            return;
        }
        System.out.println("Backend is up.\n");

        // A fresh, empty history for this session: the resolution-history tool
        // will genuinely have nothing to report, so any decision you see is the
        // model reasoning over the conflict facts you typed, not memorized data.
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();
        List<AgentTool> tools = List.of(new ConflictContextTool(), new AuditHistoryTool(history));
        AgenticConflictResolutionAdvisor advisor =
                new AgenticConflictResolutionAdvisor(model, tools, config, AgenticTraceSink.NONE);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int round = 1;
        while (true) {
            System.out.println("--- Conflict #" + round + " ---");
            String feature = ask(in, "Feature name", "description");
            String sourceValue = ask(in, "SOURCE branch value (the incoming change)",
                    "Customer Order Management Service - handles order lifecycle");
            String targetValue = ask(in, "TARGET branch value (the current branch)",
                    "temp value, will fix later");
            String mixed = ask(in,
                    "Origins - both ORIGINAL (Enter), or 'mixed' for source=ORIGINAL/target=CONSEQUENTIAL", "");

            ChangeOrigin targetOrigin = "mixed".equalsIgnoreCase(mixed.trim())
                    ? ChangeOrigin.CONSEQUENTIAL : ChangeOrigin.ORIGINAL;
            UpdateConflict conflict = buildConflict(feature, sourceValue, targetValue, targetOrigin);

            if (targetOrigin == ChangeOrigin.CONSEQUENTIAL) {
                System.out.println();
                System.out.println("Note: this is a mixed-origin conflict. Vitruvius's deterministic Tier-1 rule");
                System.out.println("(ORIGINAL beats CONSEQUENTIAL) would normally resolve this without ever");
                System.out.println("calling the model; propose() below calls the advisor directly so you can");
                System.out.println("still see how it reasons about the same facts.");
            }

            System.out.println();
            System.out.println(">>> Resolving - watch the live log below for each tool call the model makes:");
            System.out.println();

            long startNanos = System.nanoTime();
            Optional<ResolutionProposal> proposal = advisor.propose(conflict);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            System.out.println();
            System.out.println("=== Result (" + elapsedMs + " ms) ===");
            if (proposal.isPresent()) {
                ResolutionProposal p = proposal.get();
                boolean keptSource = p.chosenEntry() == conflict.getSourceEntry();
                System.out.println("Kept       : " + (keptSource ? "SOURCE" : "TARGET")
                        + " -> \"" + p.chosenEntry().getTo() + "\"");
                System.out.println("Confidence : " + p.confidence());
                System.out.println("Rationale  : " + p.rationale());
            } else {
                System.out.println("No opinion — the model was not confident enough to pick a side "
                        + "(or explicitly deferred), so this would go to a human.");
            }
            System.out.println();

            String again = ask(in, "Run another? [Y/n]", "Y");
            if ("n".equalsIgnoreCase(again.trim())) {
                break;
            }
            round++;
            System.out.println();
        }
        System.out.println("Bye.");
    }

    private static String ask(BufferedReader in, String prompt, String defaultValue) throws IOException {
        System.out.print(prompt + (defaultValue.isEmpty() ? "" : " [" + defaultValue + "]") + ": ");
        System.out.flush();
        String line = in.readLine();
        return (line == null || line.isBlank()) ? defaultValue : line;
    }

    private static UpdateConflict buildConflict(String feature, String sourceValue, String targetValue,
                                                ChangeOrigin targetOrigin) {
        SemanticChangeEntry source = entry(feature, sourceValue, ChangeOrigin.ORIGINAL);
        SemanticChangeEntry target = entry(feature, targetValue, targetOrigin);
        return new UpdateConflict("playground-element", "entities::Service", feature,
                "feature-branch", "main", source, target);
    }

    private static SemanticChangeEntry entry(String feature, String to, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType(SemanticChangeType.ATTRIBUTE_CHANGED.name())
                .elementUuid("playground-element")
                .eClass("entities::Service")
                .feature(feature)
                .from("(previous value)")
                .to(to)
                .origin(origin)
                .build();
    }

    /**
     * Walks upward from the current working directory looking for
     * {@code .vitruvius/config/agentic-advisor.json}, since running from the
     * {@code vsum} module directory (the common case) puts the actual project
     * root one level up.
     */
    private static Path findProjectConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(".vitruvius").resolve("config");
            if (Files.exists(candidate.resolve("agentic-advisor.json"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
