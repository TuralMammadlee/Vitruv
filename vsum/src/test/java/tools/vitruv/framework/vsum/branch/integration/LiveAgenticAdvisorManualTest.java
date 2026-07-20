package tools.vitruv.framework.vsum.branch.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.MergeManager;
import tools.vitruv.framework.vsum.branch.agentic.AgenticAdvisorConfig;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.ModelMergeResult;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.CommitInfo;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument.FileChangeInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.commitFile;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.initRepo;

/**
 * Manual, opt-in end-to-end check that a developer's own hosted-LLM setup
 * actually works through the <em>real</em> production wiring — the
 * {@code ConflictResolutionAdvisorProvider} {@code ServiceLoader} discovery
 * that a live {@code new MergeManager(repoRoot)} goes through — as opposed to
 * {@code AgenticAdvisorFactory.build(...)} being called by hand, which is what
 * the eval harness and most unit tests do.
 *
 * <p>This is intentionally <b>not</b> a hermetic unit test: it makes a real
 * network call to whatever backend the developer has configured. Two
 * independent gates keep it inert for everyone else:
 * <ol>
 *   <li>{@link EnabledIfEnvironmentVariable} skips the whole class unless
 *       {@code VITRUV_LLM_API_KEY} is exported in the environment running the
 *       test — true for CI and for any teammate who hasn't set up a hosted
 *       backend.</li>
 *   <li>An {@link Assumptions#assumeTrue} check further skips unless the
 *       <em>project's own</em> {@code .vitruvius/config/agentic-advisor.json}
 *       is present, enabled, and set to {@code provider: "openai"} — so no
 *       university hostname or model name is hardcoded here; it always
 *       exercises whatever the developer actually configured.</li>
 * </ol>
 *
 * <p>To run it deliberately:
 * <pre>
 *   mvn -pl vsum -Dtest=LiveAgenticAdvisorManualTest test
 * </pre>
 * with {@code VITRUV_LLM_API_KEY} exported in that shell and a real
 * {@code .vitruvius/config/agentic-advisor.json} (provider {@code openai}) at
 * the repository root.
 */
@Tag("live")
class LiveAgenticAdvisorManualTest {

    @Test
    @DisplayName("a real MergeManager auto-discovers the hosted agentic advisor and resolves a live conflict")
    @EnabledIfEnvironmentVariable(named = "VITRUV_LLM_API_KEY", matches = ".+")
    void hostedAdvisorResolvesARealUpdateConflict(@TempDir Path repoDir) throws Exception {
        Path foundConfigDir = findProjectConfigDir();
        Assumptions.assumeTrue(foundConfigDir != null,
                "No .vitruvius/config/agentic-advisor.json found above the test working directory; "
                        + "create one with provider=openai to run this test.");
        Path realConfigDir = Objects.requireNonNull(foundConfigDir,
                "unreachable: assumeTrue above already skipped the test when null");

        AgenticAdvisorConfig realConfig = AgenticAdvisorConfig.load(realConfigDir);
        Assumptions.assumeTrue(realConfig.isEnabled() && realConfig.isOpenAiProvider(),
                "agentic-advisor.json is not enabled with provider=openai; skipping the live check.");

        System.out.println("=== Live agentic advisor check ===");
        System.out.println("endpoint : " + realConfig.getEndpoint());
        System.out.println("model    : " + realConfig.getModel());
        System.out.println("apiKeyEnv: " + realConfig.getApiKeyEnv() + " (value not printed)");

        try (Git git = initRepo(repoDir)) {
            // Reuse the developer's own real config, so MergeManager's ServiceLoader
            // discovery path installs the advisor exactly as it would in production.
            Path testConfigDir = repoDir.resolve(".vitruvius").resolve("config");
            Files.createDirectories(testConfigDir);
            Files.copy(realConfigDir.resolve("agentic-advisor.json"),
                    testConfigDir.resolve("agentic-advisor.json"));

            // Two branches make a same-origin (O_O) edit to the same feature, so
            // neither the deterministic origin rule (Tier 1) nor the domain
            // validator (Tier 2, none registered) can resolve it — the conflict can
            // only be settled by the audit-history baseline or, since this is a
            // fresh repo with no history, by the live LLM (Tier 3).
            commitFile(git, repoDir, "system.model", "<System/>", "Base version");
            git.branchCreate().setName("feature").call();

            git.checkout().setName("feature").call();
            String featureSha = commitFile(git, repoDir, "system.model",
                    "<System v='feature'/>", "Feature change");

            git.checkout().setName("master").call();
            String masterSha = commitFile(git, repoDir, "system.model",
                    "<System v='master'/>", "Master change");

            writeChangelog(repoDir, "feature", featureSha,
                    entry("service-1", "description", ChangeOrigin.ORIGINAL,
                            "TBD", "Customer Order Management Service - handles order lifecycle"));
            writeChangelog(repoDir, "master", masterSha,
                    entry("service-1", "description", ChangeOrigin.ORIGINAL,
                            "TBD", "temp value, will fix later"));

            // new MergeManager(...) is the real production entry point: its
            // constructor runs ServiceLoader discovery over
            // ConflictResolutionAdvisorProvider and installs the agentic stack
            // because testConfigDir now carries an enabled config — no manual
            // AgenticAdvisorFactory call anywhere in this test.
            MergeManager manager = new MergeManager(repoDir);
            ModelMergeResult mergeResult = manager.merge("feature");

            assertEquals(ModelMergeResult.MergeStatus.CONFLICTING, mergeResult.getStatus(),
                    "system.model differs on both branches -> CONFLICTING");
            assertEquals(1, manager.getLastUpdateConflicts().size(),
                    "exactly one same-origin update conflict on service-1.description");

            UpdateConflict conflict = manager.getLastUpdateConflicts().get(0);
            System.out.println();
            System.out.println("Conflict: " + conflict.getEClass() + "." + conflict.getFeatureName()
                    + " [" + conflict.getOriginPermutation() + "]");
            System.out.println("  source (feature): " + conflict.getSourceEntry().getTo());
            System.out.println("  target (master) : " + conflict.getTargetEntry().getTo());

            long startNanos = System.nanoTime();
            AutoResolutionOutcome outcome = manager.resolveUpdateConflicts();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            System.out.println();
            System.out.println("=== Live advisor result (" + elapsedMs + " ms) ===");
            if (!outcome.getAutoResolved().isEmpty()) {
                var resolved = outcome.getAutoResolved().get(0);
                System.out.println("Decision : AUTO-RESOLVED");
                System.out.println("Kept     : " + resolved.chosenEntry().getTo());
                System.out.println("Reason   : " + resolved.reason());
            } else {
                System.out.println("Decision : left for manual resolution (advisor abstained or was below threshold)");
                Optional<tools.vitruv.framework.vsum.branch.data.ResolutionProposal> advisory =
                        outcome.getAdvisoryProposal(conflict);
                advisory.ifPresent(p -> System.out.println(
                        "Advisory hint: keep '" + p.chosenEntry().getTo() + "' (confidence " + p.confidence()
                                + ") — " + p.rationale()));
            }

            printAgenticTrace(repoDir);

            assertEquals(1, outcome.totalCount(), "the one conflict must have been evaluated");
            assertNotNull(manager.getSeverityThresholds(), "sanity check: manager finished constructing normally");
        }
    }

    /** Prints the full agentic reasoning trace (tool calls + final decision) written by the run. */
    private static void printAgenticTrace(Path repoDir) throws IOException {
        Path auditDir = repoDir.resolve(".vitruvius").resolve("audit");
        if (!Files.isDirectory(auditDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(auditDir)) {
            List<Path> traces = files
                    .filter(p -> p.getFileName().toString().endsWith(".agentic-trace.json"))
                    .toList();
            for (Path trace : traces) {
                System.out.println();
                System.out.println("=== Agentic trace: " + trace.getFileName() + " ===");
                System.out.println(Files.readString(trace));
            }
        }
    }

    /**
     * Walks upward from the current working directory looking for
     * {@code .vitruvius/config/agentic-advisor.json}, since Maven/IDE test
     * runners set the working directory to the module root ({@code vsum/}),
     * one level below the actual project root where the config lives.
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

    private static void writeChangelog(Path repoDir, String branch, String fullSha,
                                       SemanticChangeEntry... entries) throws IOException {
        String shortSha = fullSha.substring(0, 7);
        Path jsonDir = repoDir.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
        Files.createDirectories(jsonDir);

        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = "1.1";
        doc.commit = new CommitInfo();
        doc.commit.sha = fullSha;
        doc.commit.shortSha = shortSha;
        doc.commit.branch = branch;

        FileChangeInfo fc = new FileChangeInfo();
        fc.operation = "MODIFIED";
        fc.path = "system.model";
        fc.semanticChanges = new ArrayList<>(List.of(entries));
        doc.fileChanges = new ArrayList<>(List.of(fc));

        Gson gson = new GsonBuilder().setPrettyPrinting()
                .registerTypeAdapter(ChangeOrigin.class,
                        (JsonSerializer<ChangeOrigin>) (src, t, c) -> new JsonPrimitive(src.name()))
                .create();
        Files.writeString(jsonDir.resolve(shortSha + ".json"), gson.toJson(doc));
    }

    private static SemanticChangeEntry entry(String elementUuid, String feature,
                                             ChangeOrigin origin, String from, String to) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType(SemanticChangeType.ATTRIBUTE_CHANGED.name())
                .elementUuid(elementUuid)
                .eClass("entities::Service")
                .feature(feature)
                .from(from)
                .to(to)
                .origin(origin)
                .build();
    }
}
