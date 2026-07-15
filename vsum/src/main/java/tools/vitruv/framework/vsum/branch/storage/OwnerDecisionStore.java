package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.OwnerDecision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Persists and reads {@link OwnerDecision} records for escalated deletion
 * conflicts (activity-diagram diamond {@code d_owner}).
 *
 * <p>Decisions are stored as individual JSON files under
 * {@code .vitruvius/owner-decisions/<source>-into-<target>-<elementUuid>.json}.
 * An owner records a decision out of band (e.g. by calling
 * {@code MergeManager.submitOwnerDecision}) and the merge resolution pass
 * reads it back to decide whether to unblock or keep the merge blocked.
 */
public class OwnerDecisionStore {

    private static final Logger LOGGER = LogManager.getLogger(OwnerDecisionStore.class);

    private final Path repoRoot;
    private final Gson gson;

    /**
     * Creates a decision store rooted at the given Git repository.
     *
     * @param repoRoot the root directory of the Git repository.
     */
    public OwnerDecisionStore(Path repoRoot) {
        this.repoRoot = checkNotNull(repoRoot, "repoRoot must not be null");
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    /**
     * Writes the owner's decision to disk. Uses {@link StandardOpenOption#SYNC}
     * so the decision is durably on disk before this method returns.
     *
     * @param decision the decision to persist.
     * @return the path of the written decision artifact.
     * @throws IOException if the artifact cannot be written.
     */
    public Path save(OwnerDecision decision) throws IOException {
        Objects.requireNonNull(decision, "decision must not be null");

        String filename = sanitize(decision.getSourceBranch()) + "-into-"
                + sanitize(decision.getTargetBranch()) + "-"
                + sanitize(decision.getElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("owner-decisions").resolve(filename);

        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                gson.toJson(decision),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);

        LOGGER.info("Owner decision saved: {} -> {} by {}", decision.getDecision(),
                file.getFileName(), decision.getOwnerId());
        return file;
    }

    /**
     * Reads a previously recorded decision for the given conflict, if one exists.
     *
     * @param elementUuid  UUID of the conflicting deleted element.
     * @param sourceBranch the merge source branch.
     * @param targetBranch the merge target branch.
     * @return the stored decision, or empty if none has been recorded yet.
     * @throws IOException if the decision file exists but cannot be read.
     */
    public Optional<OwnerDecision> load(String elementUuid, String sourceBranch, String targetBranch)
            throws IOException {
        String filename = sanitize(sourceBranch) + "-into-"
                + sanitize(targetBranch) + "-"
                + sanitize(elementUuid) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("owner-decisions").resolve(filename);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String json = Files.readString(file);
        return Optional.of(gson.fromJson(json, OwnerDecision.class));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
