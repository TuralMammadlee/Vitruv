package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Writes conflict-resolution decisions to a persistent JSON audit log.
 *
 * <p>Each merge session should create one {@code AuditLogger} instance, call
 * {@link #log(AuditLogEntry)} for every decision made, and then call
 * {@link #flush()} <em>before</em> triggering any JGit operation that modifies
 * the working tree (e.g., ancestor recovery).  This ordering guarantees that the
 * human's stated rationale is durably on disk even if the subsequent Git
 * operation fails or the process is killed.
 *
 * <p>Audit log files are written to
 * {@code .vitruvius/audit/<sourceBranch>-into-<targetBranch>-<epochMilli>.audit.json}
 * using {@link StandardOpenOption#SYNC} so the OS page cache is bypassed and the
 * bytes land on the storage device before {@link #flush()} returns.
 *
 * <p>Log entries are serialized as a JSON array.  Null fields (such as the
 * {@code rationale} of an auto-resolved entry) are written explicitly as
 * {@code null} because the serializer is configured with
 * {@link com.google.gson.GsonBuilder#serializeNulls()}, which keeps every
 * entry's shape uniform and self-describing on disk.
 */
public class AuditLogger {

    private static final Logger LOGGER = LogManager.getLogger(AuditLogger.class);

    private final Path auditFile;
    private final Gson gson;
    private final List<AuditLogEntry> entries = new ArrayList<>();

    /**
     * Creates an AuditLogger for the given merge context.
     *
     * @param repoRoot     root directory of the Git repository.
     * @param sourceBranch the incoming (source) branch of the merge.
     * @param targetBranch the current (target) branch of the merge.
     */
    public AuditLogger(Path repoRoot, String sourceBranch, String targetBranch) {
        checkNotNull(repoRoot,     "repoRoot must not be null");
        checkNotNull(sourceBranch, "sourceBranch must not be null");
        checkNotNull(targetBranch, "targetBranch must not be null");

        String safeSource = sanitize(sourceBranch);
        String safeTarget = sanitize(targetBranch);
        String filename = safeSource + "-into-" + safeTarget + "-" + Instant.now().toEpochMilli() + ".audit.json";
        this.auditFile = repoRoot.resolve(".vitruvius").resolve("audit").resolve(filename);
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    /**
     * Appends one entry to the in-memory list.  Does not write to disk.
     *
     * @param entry the audit entry to record; must not be null.
     */
    public void log(AuditLogEntry entry) {
        entries.add(Objects.requireNonNull(entry, "entry must not be null"));
    }

    /**
     * Writes all accumulated entries to disk as a JSON array and returns the
     * path of the written file.
     *
     * <p>The write uses {@link StandardOpenOption#SYNC} to ensure that the
     * audit record is physically committed to the storage device before this
     * method returns.  Callers must invoke this method <em>before</em> any
     * JGit operation that modifies the working tree.
     *
     * @return path of the written audit file.
     * @throws IOException if the file cannot be created or written.
     */
    public Path flush() throws IOException {
        Files.createDirectories(auditFile.getParent());
        Files.writeString(
                auditFile,
                gson.toJson(entries),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);
        LOGGER.info("Audit log flushed synchronously: {} ({} entry/entries)",
                auditFile.getFileName(), entries.size());
        return auditFile;
    }

    /** Returns {@code true} when no entries have been logged yet. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Returns the number of entries currently queued. */
    public int size() {
        return entries.size();
    }

    /** Returns the path the audit file will be (or was) written to. */
    public Path getAuditFile() {
        return auditFile;
    }

    private static String sanitize(String branchName) {
        return branchName.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
