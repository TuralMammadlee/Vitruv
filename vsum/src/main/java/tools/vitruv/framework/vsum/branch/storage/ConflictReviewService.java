package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.ConflictReview;
import tools.vitruv.framework.vsum.branch.data.ConflictReview.ReviewChange;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.data.SeverityReport;
import tools.vitruv.framework.vsum.branch.data.SeverityThresholds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Builds and persists the review package for an escalated deletion conflict
 * (activity-diagram box {@code owner_review}).
 *
 * <p>Assembles conflict history (changelog entries from both merge branches),
 * change-level state previews (the {@code from}/{@code to} of each affected
 * update), and a {@link SeverityReport}, then writes the result to
 * {@code .vitruvius/reviews/<source>-into-<target>-<elementUuid>.json}.
 */
public class ConflictReviewService {

    private static final Logger LOGGER = LogManager.getLogger(ConflictReviewService.class);

    private final Path repoRoot;
    private final SemanticChangelogManager changelogManager;
    private final Gson gson;

    /**
     * Creates a review service for the given repository.
     *
     * @param repoRoot          the root directory of the Git repository.
     * @param changelogManager  the changelog manager used to read branch history.
     */
    public ConflictReviewService(Path repoRoot, SemanticChangelogManager changelogManager) {
        this.repoRoot = checkNotNull(repoRoot, "repoRoot must not be null");
        this.changelogManager = checkNotNull(changelogManager, "changelogManager must not be null");
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    /**
     * Builds a {@link ConflictReview} for the given conflict by reading the
     * changelogs at the supplied short SHAs and filtering entries that touch
     * the deleted element or its affected updates.
     *
     * @param conflict        the escalated deletion conflict.
     * @param thresholds      the severity thresholds used for the report.
     * @param role            the role whose clearance was insufficient.
     * @param sourceBranch    the merge source branch.
     * @param targetBranch    the merge target branch.
     * @param sourceShortSha  the short SHA of the source branch head changelog.
     * @param targetShortSha  the short SHA of the target branch head changelog.
     * @return the assembled review package.
     * @throws IOException if a changelog cannot be read.
     */
    public ConflictReview buildReview(DeletionConflict conflict, SeverityThresholds thresholds,
                                      RoleDefinition role, String sourceBranch, String targetBranch,
                                      String sourceShortSha, String targetShortSha) throws IOException {
        Objects.requireNonNull(conflict, "conflict must not be null");
        Objects.requireNonNull(role, "role must not be null");

        SeverityReport severityReport = SeverityReport.forConflict(conflict, thresholds, role);

        Set<String> relevantUuids = collectRelevantUuids(conflict);
        List<ReviewChange> history = new ArrayList<>();
        history.addAll(collectHistory(sourceBranch, sourceShortSha, relevantUuids));
        history.addAll(collectHistory(targetBranch, targetShortSha, relevantUuids));

        List<ReviewChange> statePreviews = buildStatePreviews(conflict);

        return new ConflictReview(
                conflict.getDeletedElementUuid(),
                conflict.getDeletedElementEClass(),
                conflict.getDeletingBranch(),
                conflict.getUpdatingBranch(),
                conflict.getDetectedOwners(),
                conflict.getDeletionOrigin() != null ? conflict.getDeletionOrigin().name() : null,
                conflict.isAncestorAvailable(),
                severityReport,
                history,
                statePreviews);
    }

    /**
     * Builds the review, writes it to disk, and logs a summary. The write uses
     * {@link StandardOpenOption#SYNC} so the artifact is durably on disk before
     * this method returns.
     *
     * @return the path of the written review artifact.
     * @throws IOException if the review cannot be built or written.
     */
    public Path persistReview(ConflictReview review, String sourceBranch, String targetBranch)
            throws IOException {
        Objects.requireNonNull(review, "review must not be null");

        String filename = sanitize(sourceBranch) + "-into-"
                + sanitize(targetBranch) + "-"
                + sanitize(review.getDeletedElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("reviews").resolve(filename);

        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                gson.toJson(review),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);

        LOGGER.info("Conflict review written: {} | {}-severity, {} history entry/entries, {} preview(s), ancestorRecoverable={}",
                file.getFileName(),
                review.getSeverityReport().getSeverity(),
                review.getHistory().size(),
                review.getStatePreviews().size(),
                review.isAncestorRecoverable());
        return file;
    }

    /**
     * Returns whether a review artifact already exists for this conflict.
     */
    public boolean reviewExists(ConflictReview review, String sourceBranch, String targetBranch) {
        Objects.requireNonNull(review, "review must not be null");
        String filename = sanitize(sourceBranch) + "-into-"
                + sanitize(targetBranch) + "-"
                + sanitize(review.getDeletedElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("reviews").resolve(filename);
        return Files.exists(file);
    }

    private Set<String> collectRelevantUuids(DeletionConflict conflict) {
        Set<String> uuids = new HashSet<>();
        uuids.add(conflict.getDeletedElementUuid());
        for (SemanticChangeEntry update : conflict.getAffectedUpdates()) {
            if (update.getElementUuid() != null) {
                uuids.add(update.getElementUuid());
            }
            if (update.getContainerUuid() != null) {
                uuids.add(update.getContainerUuid());
            }
        }
        return uuids;
    }

    private List<ReviewChange> collectHistory(String branch, String shortSha, Set<String> relevantUuids)
            throws IOException {
        List<ReviewChange> changes = new ArrayList<>();
        if (shortSha == null || "unknown".equals(shortSha)) {
            return changes;
        }
        SemanticChangelogManager.ChangelogDocument doc = changelogManager.read(branch, shortSha);
        if (doc == null || doc.fileChanges == null) {
            return changes;
        }
        for (SemanticChangelogManager.ChangelogDocument.FileChangeInfo fileChange : doc.fileChanges) {
            if (fileChange.semanticChanges == null) {
                continue;
            }
            for (SemanticChangeEntry entry : fileChange.semanticChanges) {
                if (entry.getElementUuid() != null && relevantUuids.contains(entry.getElementUuid())) {
                    changes.add(toReviewChange(branch, entry));
                }
            }
        }
        return changes;
    }

    private List<ReviewChange> buildStatePreviews(DeletionConflict conflict) {
        List<ReviewChange> previews = new ArrayList<>();
        for (SemanticChangeEntry update : conflict.getAffectedUpdates()) {
            previews.add(toReviewChange(conflict.getUpdatingBranch(), update));
        }
        return previews;
    }

    private static ReviewChange toReviewChange(String branch, SemanticChangeEntry entry) {
        return new ReviewChange(
                branch,
                entry.getElementUuid(),
                entry.getEClass(),
                entry.getFeature(),
                entry.getChangeType() != null ? entry.getChangeType().name() : null,
                entry.getOrigin() != null ? entry.getOrigin().name() : null,
                entry.getFrom(),
                entry.getTo());
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
