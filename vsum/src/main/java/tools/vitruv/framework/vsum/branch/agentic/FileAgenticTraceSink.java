package tools.vitruv.framework.vsum.branch.agentic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * {@link AgenticTraceSink} that writes each finished {@link AgenticTrace} as a
 * pretty-printed JSON file under {@code .vitruvius/audit/}, alongside the
 * existing {@code *.audit.json} conflict-resolution log.
 *
 * <p>File name pattern:
 * {@code <sanitized-signature>-<elementUuid>-<epochMilli>.agentic-trace.json}.
 * Keeping traces next to the audit log means every auto-resolution the agent
 * influenced can be explained after the fact by reading one directory.
 *
 * <p>Writes are best-effort: a failure is logged but never thrown, so trace
 * persistence can never affect a merge. The write uses {@link StandardOpenOption#SYNC}
 * to match the durability guarantee of {@code AuditLogger}.
 */
public final class FileAgenticTraceSink implements AgenticTraceSink {

    private static final Logger LOGGER = LogManager.getLogger(FileAgenticTraceSink.class);

    private final Path auditDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    /**
     * Creates a sink writing under {@code <repoRoot>/.vitruvius/audit/}.
     *
     * @param repoRoot the Git repository root, never null.
     */
    public FileAgenticTraceSink(Path repoRoot) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        this.auditDir = repoRoot.resolve(".vitruvius").resolve("audit");
    }

    @Override
    public void accept(AgenticTrace trace) {
        if (trace == null) {
            return;
        }
        try {
            Files.createDirectories(auditDir);
            String filename = sanitize(trace.getConflictSignature())
                    + "-" + sanitize(trace.getElementUuid())
                    + "-" + Instant.now().toEpochMilli()
                    + ".agentic-trace.json";
            Path file = auditDir.resolve(filename);
            Files.writeString(file, gson.toJson(trace), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC);
            LOGGER.debug("Agentic trace written: {} (outcome={})", file.getFileName(), trace.getOutcome());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to write agentic trace: {}", e.getMessage());
        }
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }
}
