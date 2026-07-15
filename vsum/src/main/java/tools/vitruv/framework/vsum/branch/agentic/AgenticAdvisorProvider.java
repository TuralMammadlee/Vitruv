package tools.vitruv.framework.vsum.branch.agentic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisorProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link ConflictResolutionAdvisorProvider} that plugs the full agentic advisor
 * stack (statistical audit-history baseline, then the local-LLM agent) into any
 * {@code MergeManager} whose repository has opted in.
 *
 * <p><b>Opt-in marker:</b> the file
 * {@code <repoRoot>/.vitruvius/config/agentic-advisor.json} with
 * {@code "enabled": true}. Repositories without the file — including every unit
 * test fixture — keep the exact rule-only behaviour they had before, so
 * enabling the LLM in the loop is a configuration act, not a code change.
 *
 * <p>The provided advisor feeds itself: its resolution history is read from the
 * persisted {@code .vitruvius/audit} log and its element-history tool from the
 * persisted changelogs, so no manual data preparation is involved at any point.
 *
 * <p>Registered via
 * {@code META-INF/services/tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisorProvider}.
 */
public final class AgenticAdvisorProvider implements ConflictResolutionAdvisorProvider {

    private static final Logger LOGGER = LogManager.getLogger(AgenticAdvisorProvider.class);

    private static final String CONFIG_FILE = "agentic-advisor.json";

    @Override
    public Optional<ConflictResolutionAdvisor> createAdvisor(Path repoRoot) {
        try {
            Path configDir = repoRoot.resolve(".vitruvius").resolve("config");
            if (!Files.exists(configDir.resolve(CONFIG_FILE))) {
                return Optional.empty();  // repository has not opted in
            }
            AgenticAdvisorConfig config = AgenticAdvisorConfig.load(configDir);
            if (!config.isEnabled()) {
                LOGGER.info("Agentic advisor config present but disabled for {}", repoRoot);
                return Optional.empty();
            }
            LOGGER.info("Agentic advisor enabled for {} (model '{}')", repoRoot, config.getModel());
            return Optional.of(AgenticAdvisorFactory.build(repoRoot, config));
        } catch (Exception e) {
            LOGGER.warn("Could not build agentic advisor for {}; merges continue rule-only: {}",
                    repoRoot, e.getMessage());
            return Optional.empty();
        }
    }
}
