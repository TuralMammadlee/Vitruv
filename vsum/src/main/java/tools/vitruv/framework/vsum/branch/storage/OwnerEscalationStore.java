package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.OwnerEscalation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Persists {@link OwnerEscalation} records for the clearance-denied workflow.
 */
public class OwnerEscalationStore {

    private static final Logger LOGGER = LogManager.getLogger(OwnerEscalationStore.class);

    private final Path repoRoot;
    private final Gson gson;

    public OwnerEscalationStore(Path repoRoot) {
        this.repoRoot = checkNotNull(repoRoot, "repoRoot must not be null");
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    public Path save(OwnerEscalation escalation) throws IOException {
        Objects.requireNonNull(escalation, "escalation must not be null");

        String filename = sanitize(escalation.getSourceBranch()) + "-into-"
                + sanitize(escalation.getTargetBranch()) + "-"
                + sanitize(escalation.getElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("owner-escalations").resolve(filename);

        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                gson.toJson(escalation),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);

        LOGGER.info("Owner escalation saved: {} -> {} ({})",
                escalation.getStatus(), file.getFileName(), escalation.getAssignees());
        return file;
    }

    public Optional<OwnerEscalation> load(String elementUuid, String sourceBranch, String targetBranch)
            throws IOException {
        String filename = sanitize(sourceBranch) + "-into-"
                + sanitize(targetBranch) + "-"
                + sanitize(elementUuid) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("owner-escalations").resolve(filename);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(gson.fromJson(Files.readString(file), OwnerEscalation.class));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
