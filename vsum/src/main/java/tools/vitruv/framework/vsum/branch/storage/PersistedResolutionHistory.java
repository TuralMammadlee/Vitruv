package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@link ResolutionHistory} backed by the persisted audit log on disk.
 *
 * <p>Every merge session's decisions are written by {@link AuditLogger} to
 * {@code <repoRoot>/.vitruvius/audit/*.audit.json}. This class scans that
 * directory, extracts the update-conflict decisions (entries carrying a
 * {@code conflictSignature} and a {@code chosenSide}), and aggregates them per
 * {@link ResolutionHistory#signature(UpdateConflict) signature}. Nothing has to
 * be recorded manually: the evidence the learned advisors and the agentic LLM
 * consume is exactly the trail the pipeline already produces, so the system
 * feeds itself and its knowledge survives restarts.
 *
 * <p>Files are re-parsed only when their size or last-modified time changes;
 * unchanged files are served from an in-memory cache, so repeated queries during
 * a merge are cheap. Unreadable or legacy-format files (written before the
 * signature fields existed) are skipped with a warning — they can never fail a
 * merge.
 *
 * <p>This class is not thread-safe; use one instance per merge session, which
 * matches how {@code MergeManager} runs resolutions.
 */
public final class PersistedResolutionHistory implements ResolutionHistory {

    private static final Logger LOGGER = LogManager.getLogger(PersistedResolutionHistory.class);

    private static final String AUDIT_FILE_SUFFIX = ".audit.json";

    /** One parsed update decision, tagged with its signature for aggregation. */
    private record ParsedDecision(String signature, RecordedDecision decision) {
    }

    /** Cache of one audit file's parsed decisions, invalidated by size/mtime. */
    private record CachedFile(long size, long lastModifiedMillis, List<ParsedDecision> decisions) {
    }

    private final Path auditDir;
    private final Map<Path, CachedFile> cache = new HashMap<>();

    /**
     * Creates a history reading from {@code <repoRoot>/.vitruvius/audit/}.
     * The directory does not have to exist yet; a missing directory simply
     * means "no history".
     *
     * @param repoRoot the Git repository root, never null.
     */
    public PersistedResolutionHistory(Path repoRoot) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        this.auditDir = repoRoot.resolve(".vitruvius").resolve("audit");
    }

    @Override
    public Outcomes outcomesFor(UpdateConflict conflict) {
        String signature = ResolutionHistory.signature(conflict);
        int sourceWins = 0;
        int targetWins = 0;
        for (ParsedDecision parsed : allDecisions()) {
            if (!signature.equals(parsed.signature())) {
                continue;
            }
            if (AuditLogEntry.SIDE_SOURCE.equals(parsed.decision().chosenSide())) {
                sourceWins++;
            } else {
                targetWins++;
            }
        }
        return new Outcomes(sourceWins, targetWins);
    }

    @Override
    public List<RecordedDecision> decisionsFor(UpdateConflict conflict, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0 but was " + limit);
        }
        String signature = ResolutionHistory.signature(conflict);
        return allDecisions().stream()
                .filter(parsed -> signature.equals(parsed.signature()))
                .map(ParsedDecision::decision)
                .sorted(Comparator.comparing(
                        (RecordedDecision d) -> d.timestamp() == null ? "" : d.timestamp()).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Returns every update decision currently recorded on disk, refreshing the
     * per-file cache for files that appeared or changed since the last call.
     */
    private List<ParsedDecision> allDecisions() {
        if (!Files.isDirectory(auditDir)) {
            return List.of();
        }
        List<ParsedDecision> decisions = new ArrayList<>();
        try (Stream<Path> files = Files.list(auditDir)) {
            files.filter(file -> file.getFileName().toString().endsWith(AUDIT_FILE_SUFFIX))
                    .forEach(file -> decisions.addAll(decisionsOf(file)));
        } catch (IOException e) {
            LOGGER.warn("Could not list audit directory {}: {}", auditDir, e.getMessage());
        }
        return decisions;
    }

    private List<ParsedDecision> decisionsOf(Path file) {
        try {
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            CachedFile cached = cache.get(file);
            if (cached != null && cached.size() == size && cached.lastModifiedMillis() == lastModified) {
                return cached.decisions();
            }
            List<ParsedDecision> parsed = parse(file);
            cache.put(file, new CachedFile(size, lastModified, parsed));
            return parsed;
        } catch (IOException e) {
            LOGGER.warn("Skipping unreadable audit file {}: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Parses one audit file. Entries without a {@code conflictSignature} or
     * {@code chosenSide} (deletion decisions, legacy-format files) are silently
     * skipped: they carry no update evidence.
     */
    private List<ParsedDecision> parse(Path file) throws IOException {
        String json = Files.readString(file);
        List<ParsedDecision> decisions = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return List.of();
            }
            JsonArray entries = root.getAsJsonArray();
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String signature = stringField(entry, "conflictSignature");
                String chosenSide = stringField(entry, "chosenSide");
                if (signature == null || chosenSide == null || !isKnownSide(chosenSide)) {
                    continue;
                }
                decisions.add(new ParsedDecision(signature, new RecordedDecision(
                        stringField(entry, "timestamp"),
                        chosenSide,
                        stringField(entry, "reason"),
                        stringField(entry, "rationale"))));
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Skipping malformed audit file {}: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
        return List.copyOf(decisions);
    }

    private static boolean isKnownSide(String side) {
        return AuditLogEntry.SIDE_SOURCE.equals(side) || AuditLogEntry.SIDE_TARGET.equals(side);
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
