package tools.vitruv.framework.vsum.branch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.BranchMetadata;
import tools.vitruv.framework.vsum.branch.data.BranchState;
import tools.vitruv.framework.vsum.branch.data.ConflictReview;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.ModelMergeResult;
import tools.vitruv.framework.vsum.branch.data.OwnerDecision;
import tools.vitruv.framework.vsum.branch.data.OwnerEscalation;
import tools.vitruv.framework.vsum.branch.data.OwnerNotification;
import tools.vitruv.framework.vsum.branch.data.SeverityThresholds;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.data.ValidationResult;
import tools.vitruv.framework.vsum.branch.exception.BranchOperationException;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;
import tools.vitruv.framework.vsum.branch.storage.AuditLogger;
import tools.vitruv.framework.vsum.branch.storage.ConflictOwnerResolver;
import tools.vitruv.framework.vsum.branch.storage.ConflictOwnershipResolver;
import tools.vitruv.framework.vsum.branch.storage.ConflictReviewService;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictAnalyzer;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;
import tools.vitruv.framework.vsum.branch.storage.OwnerDecisionStore;
import tools.vitruv.framework.vsum.branch.storage.OwnerEscalationConfig;
import tools.vitruv.framework.vsum.branch.storage.OwnerEscalationResolver;
import tools.vitruv.framework.vsum.branch.storage.OwnerEscalationStore;
import tools.vitruv.framework.vsum.branch.storage.OwnerNotifier;
import tools.vitruv.framework.vsum.branch.storage.RoleManager;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;
import tools.vitruv.framework.vsum.branch.storage.UpdateConflictAnalyzer;
import tools.vitruv.framework.vsum.branch.storage.UpdateConflictResolver;
import tools.vitruv.framework.vsum.branch.util.MergeResultFile;
import tools.vitruv.framework.vsum.branch.util.MergeTriggerFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manages Git merge operations for Vitruvius model branches.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Perform three-way merge of a source branch into the current branch </li>
 *   <li>Detect fast-forward vs non-fast-forward merges</li>
 *   <li>Return a {@link ModelMergeResult} describing the outcome including conflicts</li>
 *   <li>Mark the source branch as MERGED on success </li>
 *   <li>Optionally delete the source branch after successful merge </li>
 *   <li>Write the merge trigger file so VsumMergeWatcher validates the merged state</li>
 * </ul>
 */
public class MergeManager {

    private static final Logger LOGGER = LogManager.getLogger(MergeManager.class);
    private static final String METADATA_DIR = ".vitruvius/branches";

    private final Path repoRoot;
    private final MergeTriggerFile mergeTriggerFile;
    private final SemanticChangelogManager changelogManager;
    private final DeletionConflictAnalyzer deletionConflictAnalyzer;
    private final UpdateConflictAnalyzer updateConflictAnalyzer;
    private final ConflictOwnerResolver conflictOwnerResolver;
    private final ConflictOwnershipResolver conflictOwnershipResolver;
    private final OwnerNotifier ownerNotifier;
    private final ConflictReviewService conflictReviewService;
    private final OwnerDecisionStore ownerDecisionStore;
    private final OwnerEscalationStore ownerEscalationStore;
    private final OwnerEscalationResolver ownerEscalationResolver;

    /** Deletion conflicts detected during the most recent merge (empty if none). */
    private List<DeletionConflict> lastDeletionConflicts = List.of();

    /** Update-vs-update conflicts detected during the most recent merge (empty if none). */
    private List<UpdateConflict> lastUpdateConflicts = List.of();

    /**
     * Aggregated set of conflict owners (normalized author emails) detected via
     * blame during the most recent conflicting merge. Empty when no conflicts
     * were detected or owner detection was unavailable.
     */
    private Set<String> lastDetectedConflictOwners = Set.of();

    /** Source branch of the most recent conflicting merge (null if no conflict has been detected). */
    private String lastSourceBranch = null;

    /** Target branch of the most recent conflicting merge (null if no conflict has been detected). */
    private String lastTargetBranch = null;

    /** Short SHA of the source branch head changelog from the most recent conflicting merge. */
    private String lastSourceShortSha = null;

    /** Short SHA of the target branch head changelog from the most recent conflicting merge. */
    private String lastTargetShortSha = null;

    /** Changelog documents from the most recent conflicting merge (for per-conflict owner re-resolution). */
    private ChangelogDocument lastSourceChangelog = null;
    private ChangelogDocument lastTargetChangelog = null;

    /**
     * Audit logger scoped to the current merge session. A single instance — and
     * therefore a single audit file — is created when a conflicting merge is
     * detected, so that every deletion and update decision for that one merge is
     * recorded in one coherent JSON array. {@code null} until a conflicting merge
     * is detected.
     *
     * <p>Sharing one logger across the deletion and update resolution passes is
     * deliberate: it removes the filename collision that two independently
     * constructed loggers (both keyed on the same branches and a millisecond
     * timestamp) could otherwise produce, where the second flush would silently
     * overwrite the first.
     *
     * <p>Resolution methods are expected to run once per merge session (the
     * natural {@code merge() → resolve}* lifecycle); the logger appends and
     * re-flushes the full decision set on each pass.
     */
    private AuditLogger sessionAuditLogger = null;

    /**
     * Project-wide severity thresholds loaded from
     * {@code .vitruvius/config/severity-thresholds.json}, or defaults if no
     * config file is present.  These thresholds <em>take precedence</em> over
     * any thresholds carried by a caller-supplied {@link MergePolicy}: see
     * {@link #effectivePolicyFor(MergePolicy)} for the composition rule.
     */
    private SeverityThresholds severityThresholds = SeverityThresholds.defaults();

    /**
     * Domain validator used by the auto-resolution tier for conflicts that
     * the origin rule cannot resolve. Defaults to {@link DomainValidator#NONE}.
     */
    private DomainValidator domainValidator = DomainValidator.NONE;

    /**
     * Creates a new MergeManager for the Git repository at the given path.
     * @param repoRoot the root directory of the Git repository.
     * @throws IllegalArgumentException if the path is not a valid Git repository.
     */
    public MergeManager(Path repoRoot) {
        this.repoRoot = checkNotNull(repoRoot, "repository root must not be null");
        checkArgument(Files.isDirectory(repoRoot.resolve(".git")), "No Git repository found at: %s", repoRoot);
        this.mergeTriggerFile = new MergeTriggerFile(repoRoot);
        this.changelogManager = new SemanticChangelogManager(repoRoot);
        this.deletionConflictAnalyzer = new DeletionConflictAnalyzer();
        this.updateConflictAnalyzer = new UpdateConflictAnalyzer();
        this.conflictOwnerResolver = new ConflictOwnerResolver();
        this.conflictOwnershipResolver = new ConflictOwnershipResolver();
        this.ownerNotifier = new OwnerNotifier(repoRoot);
        this.conflictReviewService = new ConflictReviewService(repoRoot, changelogManager);
        this.ownerDecisionStore = new OwnerDecisionStore(repoRoot);
        this.ownerEscalationStore = new OwnerEscalationStore(repoRoot);
        this.ownerEscalationResolver = new OwnerEscalationResolver(ownerDecisionStore);
        loadSeverityThresholds();
    }

    /**
     * Loads the project-wide severity thresholds from
     * {@code .vitruvius/config/severity-thresholds.json} and stores them in
     * {@link #severityThresholds}. Falls back to
     * {@link SeverityThresholds#defaults()} on any failure (file missing,
     * unparseable, invariants violated) so that {@code MergeManager} construction
     * never blocks merges due to admin config problems — a warning is logged instead.
     */
    private void loadSeverityThresholds() {
        try {
            Path configDir = repoRoot.resolve(".vitruvius").resolve("config");
            this.severityThresholds = SeverityThresholds.load(configDir);
            LOGGER.debug("Severity thresholds loaded from project config: {}", severityThresholds);
        } catch (IOException e) {
            LOGGER.warn("Failed to load severity thresholds, using defaults: {}", e.getMessage());
            this.severityThresholds = SeverityThresholds.defaults();
        }
    }

    /**
     * Merges the given source branch into the current branch using a three-way merge.
     *
     * <p>On success: marks source branch as MERGED, writes merge trigger for validation.
     * <p>On fast-forward: same as success but no merge commit is created.
     * <p>On conflict: returns {@link ModelMergeResult.MergeStatus#CONFLICTING} with the
     * list of conflicting files. The developer must resolve manually and commit.
     * @param sourceBranch the name of the branch to merge into the current branch.
     * @return a {@link ModelMergeResult} describing the outcome.
     * @throws BranchOperationException if the source branch does not exist or therepository cannot be opened.
     */
    public ModelMergeResult merge(String sourceBranch) throws BranchOperationException {
        return merge(sourceBranch, false);
    }

    /**
     * Merges the given source branch into the current branch, with an option to
     * automatically delete the source branch after a successful merge.
     *
     * @param sourceBranch     the name of the branch to merge into the current branch.
     * @param deleteAfterMerge whether to delete the source branch after success.
     * @return a {@link ModelMergeResult} describing the outcome.
     * @throws BranchOperationException if the source branch does not exist or the repository cannot be opened.
     */
    public ModelMergeResult merge(String sourceBranch, boolean deleteAfterMerge) throws BranchOperationException {
        checkNotNull(sourceBranch, "source branch must not be null");
        checkArgument(!sourceBranch.isBlank(), "source branch must not be blank");
        this.lastDetectedConflictOwners = Set.of();
        this.lastSourceShortSha = null;
        this.lastTargetShortSha = null;
        try (Git git = Git.open(repoRoot.toFile())) {
            Repository repo = git.getRepository();
            // Resolve current (target) branch
            String targetBranch = repo.getBranch();
            LOGGER.info("Merging '{}' into '{}'", sourceBranch, targetBranch);
            // Verify source branch exists, base 1
            Ref sourceRef = repo.findRef("refs/heads/" + sourceBranch);
            if (sourceRef == null) {
                throw new BranchOperationException("Source branch does not exist: " + sourceBranch);
            }
            // Cannot merge a branch into itself, base 2
            if (sourceBranch.equals(targetBranch)) {
                throw new BranchOperationException("Cannot merge a branch into itself: " + sourceBranch);
            }
            // Perform three-way merge via JGit
            org.eclipse.jgit.api.MergeResult jgitResult = git.merge()
                    .include(sourceRef)
                    .setStrategy(MergeStrategy.RECURSIVE)
                    .setCommit(true)
                    .setMessage("Merge branch '" + sourceBranch + "' into '" + targetBranch + "'")
                    .call();
            ModelMergeResult result = buildResult(jgitResult, sourceBranch, targetBranch, repo);
            if (result.isSuccessful() || result.getStatus() == ModelMergeResult.MergeStatus.CONFLICTING) {
                writeMergeMetadataDirectly(result, sourceBranch, targetBranch);
            }
            LOGGER.info("Merge result: {}", result);
            // On success or fast-forward - post-merge steps
            if (result.isSuccessful()) {
                // Mark source branch as MERGED (BR-9)
                markAsMerged(sourceBranch);
                // Write merge trigger so VsumMergeWatcher validates merged state
                writeMergeTrigger(result, sourceBranch, targetBranch);
                // Optionally delete source branch (BR-5)
                if (deleteAfterMerge) {
                    deleteSourceBranch(git, sourceBranch);
                }
            }
            return result;
        } catch (GitAPIException e) {
            throw new BranchOperationException("Merge failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new BranchOperationException("Failed to open repository: " + e.getMessage(), e);
        }
    }
    /**
     * Writes merge metadata directly when merge is triggered via API,
     * since the conflict file list is available from JGit but would be
     * lost if passed through the trigger file / watcher path.
     * The watcher path (hook-triggered) writes its own metadata via PostMergeHandler.
     */
    private void writeMergeMetadataDirectly(ModelMergeResult result, String sourceBranch, String targetBranch) {
        try {
            MergeResultFile resultFile = new MergeResultFile(repoRoot);
            String sha = result.getMergeCommitSha() != null ? result.getMergeCommitSha() : "no-commit-" + System.currentTimeMillis();
            // Build a ValidationResult from the merge outcome for compatibility
            ValidationResult validationResult = result.isSuccessful()
                    ? ValidationResult.success()
                    : ValidationResult.failure(List.of("Merge resulted in conflicts: " + String.join(", ", result.getConflictingFiles())));
            resultFile.writeMetadata(sha, sourceBranch, targetBranch, validationResult, result.getConflictingFiles());
            LOGGER.info("Merge metadata written directly for commit {}", sha.substring(0, Math.min(7, sha.length())));
        } catch (IOException e) {
            LOGGER.warn("Failed to write merge metadata directly (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Translates a JGit MergeResult into a {@link ModelMergeResult}.
     */
    private ModelMergeResult buildResult(org.eclipse.jgit.api.MergeResult jgitResult, String sourceBranch, String targetBranch, Repository repo) throws IOException {

        switch (jgitResult.getMergeStatus()) {

            case FAST_FORWARD:
            case FAST_FORWARD_SQUASHED: {
                ObjectId newHead = jgitResult.getNewHead();
                String sha = newHead != null ? newHead.getName() : "";
                LOGGER.info("Fast-forward merge completed, new HEAD: {}", sha.substring(0, Math.min(7, sha.length())));
                return ModelMergeResult.fastForward(sourceBranch, targetBranch, sha);
            }

            case MERGED:
            case MERGED_SQUASHED:
            case MERGED_NOT_COMMITTED: {
                ObjectId newHead = jgitResult.getNewHead();
                String sha = newHead != null ? newHead.getName() : "";
                LOGGER.info("Merge commit created: {}", sha.substring(0, Math.min(7, sha.length())));
                return ModelMergeResult.success(sourceBranch, targetBranch, sha);
            }

            case CONFLICTING: {
                // getConflicts() returns Map<String, int[][]> - file path to conflict ranges
                // only need the file paths, not the ranges
                Map<String, int[][]> conflicts = jgitResult.getConflicts() != null ? jgitResult.getConflicts() : Map.of();

                // Open a single audit session for this merge so every deletion and update
                // decision lands in one file (see sessionAuditLogger). The logger does not
                // touch disk until flush(), so creating it here is free for conflicts that
                // are ultimately resolved without any recorded decision.
                this.lastSourceBranch = sourceBranch;
                this.lastTargetBranch = targetBranch;
                this.sessionAuditLogger = new AuditLogger(repoRoot, sourceBranch, targetBranch);

                // Conflict classification and severity are handled by UpdateConflict tags
                // (OriginPermutation + FundamentalConflictType). Auto-resolution is done
                // via resolveUpdateConflicts() after the caller inspects this result.
                List<String> conflictingFiles = new ArrayList<>(conflicts.keySet());
                LOGGER.warn("Merge resulted in {} conflict(s): {}", conflictingFiles.size(), conflictingFiles);

                // Detect conflict owners via blame against both merge participants. Owner
                // detection is "available" whenever conflicting files exist, even if blame
                // ultimately resolves no author for them.
                boolean ownerDetectionAvailable = !conflictingFiles.isEmpty();
                Ref resolvedSourceRef = repo.findRef("refs/heads/" + sourceBranch);
                ObjectId sourceCommit = resolvedSourceRef != null ? resolvedSourceRef.getObjectId() : null;
                ObjectId targetCommit = repo.resolve("HEAD");
                this.lastDetectedConflictOwners =
                        detectConflictOwners(repo, sourceCommit, targetCommit, conflicts);

                // Analyze changelogs for semantic conflicts
                ChangelogDocument sourceChangelog = null;
                ChangelogDocument targetChangelog = null;
                try {
                    String shortShaSource = sourceCommit != null ? sourceCommit.abbreviate(7).name() : "unknown";
                    String shortShaTarget = targetCommit != null ? targetCommit.abbreviate(7).name() : "unknown";
                    this.lastSourceShortSha = shortShaSource;
                    this.lastTargetShortSha = shortShaTarget;
                    sourceChangelog = changelogManager.read(sourceBranch, shortShaSource);
                    targetChangelog = changelogManager.read(targetBranch, shortShaTarget);
                    this.lastSourceChangelog = sourceChangelog;
                    this.lastTargetChangelog = targetChangelog;

                    // 1. Delete-vs-update conflicts
                    this.lastDeletionConflicts = deletionConflictAnalyzer.analyze(
                            sourceBranch, targetBranch,
                            sourceChangelog, targetChangelog,
                            /* ancestorAvailable */ true);
                    if (!lastDeletionConflicts.isEmpty()) {
                        LOGGER.warn("{} delete-vs-update conflict(s) detected",
                                lastDeletionConflicts.size());
                    }

                    // 2. Update-vs-update conflicts
                    this.lastUpdateConflicts = updateConflictAnalyzer.analyze(
                            sourceBranch, targetBranch,
                            sourceChangelog, targetChangelog);
                    if (!lastUpdateConflicts.isEmpty()) {
                        LOGGER.warn("{} update-vs-update conflict(s) detected",
                                lastUpdateConflicts.size());
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Semantic conflict analysis skipped (changelogs unavailable or unreadable): {}",
                            e.getMessage());
                    LOGGER.debug("Semantic conflict analysis failure details:", e);
                    this.lastDeletionConflicts = List.of();
                    this.lastUpdateConflicts = List.of();
                    this.lastSourceChangelog = null;
                    this.lastTargetChangelog = null;
                }

                // Per-conflict owner detection: ORIGINAL-change authors first, blame fallback,
                // then senior-role fallback when no owner is found.
                RoleManager roleManagerForOwnership;
                try {
                    roleManagerForOwnership = new RoleManager(repoRoot);
                } catch (IOException e) {
                    LOGGER.warn("Could not load roles for owner detection: {}", e.getMessage());
                    roleManagerForOwnership = null;
                }
                final ChangelogDocument srcChangelog = sourceChangelog;
                final ChangelogDocument tgtChangelog = targetChangelog;
                final RoleManager ownershipRoles = roleManagerForOwnership;
                Set<String> unionOwners = new LinkedHashSet<>();
                this.lastDeletionConflicts = this.lastDeletionConflicts.stream()
                        .map(c -> {
                            Set<String> owners = conflictOwnershipResolver.resolveOwners(
                                    c, sourceBranch, targetBranch,
                                    srcChangelog, tgtChangelog,
                                    lastDetectedConflictOwners, ownershipRoles);
                            unionOwners.addAll(owners);
                            return c.withOwnership(owners, ownerDetectionAvailable);
                        })
                        .toList();
                this.lastDetectedConflictOwners = Set.copyOf(unionOwners);
                this.lastUpdateConflicts = this.lastUpdateConflicts.stream()
                        .map(c -> c.withOwnership(lastDetectedConflictOwners, ownerDetectionAvailable))
                        .toList();

                return ModelMergeResult.conflicting(sourceBranch, targetBranch, conflictingFiles);
            }
            case ABORTED:
            case CHECKOUT_CONFLICT:
            case FAILED:
            default: {
                // getFailingPaths() returns Map<String, MergeFailureReason> for CHECKOUT_CONFLICT
                // extract the file paths and reason strings for the message
                String reason = jgitResult.getMergeStatus().toString();
                if (jgitResult.getFailingPaths() != null && !jgitResult.getFailingPaths().isEmpty()) {
                    String failingFiles = String.join(", ", jgitResult.getFailingPaths().keySet());
                    reason += " - failing paths: " + failingFiles;
                }
                LOGGER.error("Merge failed with status: {}", reason);
                return ModelMergeResult.failed(sourceBranch, targetBranch, reason);
            }
        }
    }

    /**
     * Marks the source branch metadata state as MERGED.
     * Non-fatal if the metadata file does not exist.
     */
    private void markAsMerged(String sourceBranch) {
        Path metadataFile = repoRoot.resolve(METADATA_DIR).resolve(sourceBranch + ".metadata");

        if (!Files.exists(metadataFile)) {
            LOGGER.debug("No metadata file for '{}', skipping MERGED status update", sourceBranch);
            return;
        }
        try {
            BranchMetadata metadata = BranchMetadata.readFrom(metadataFile);
            metadata.setState(BranchState.MERGED);
            metadata.writeTo(metadataFile);
            LOGGER.info("Branch '{}' marked as MERGED", sourceBranch);
        } catch (IOException e) {
            LOGGER.warn("Failed to mark branch '{}' as MERGED (non-critical): {}", sourceBranch, e.getMessage());
        }
    }

    /**
     * Writes the merge trigger file so VsumMergeWatcher picks up and validates the merged state.
     * Non-fatal if writing fails - the merge has already completed successfully.
     */
    private void writeMergeTrigger(ModelMergeResult result, String sourceBranch, String targetBranch) {
        try {
            String sha = result.getMergeCommitSha() != null ? result.getMergeCommitSha() : "fast-forward";
            mergeTriggerFile.createTrigger(sha, sourceBranch, targetBranch);
            LOGGER.debug("Merge trigger written for VsumMergeWatcher");
        } catch (IOException e) {
            LOGGER.warn("Failed to write merge trigger (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Deletes the source branch from Git after a successful merge.
     * Non-fatal if deletion fails - the merge has already completed.
     */
    private void deleteSourceBranch(Git git, String sourceBranch) {
        try {
            git.branchDelete().setBranchNames(sourceBranch).setForce(false).call(); // only delete if fully merged
                LOGGER.info("Source branch '{}' deleted after merge", sourceBranch);
        } catch (GitAPIException e) {
            LOGGER.warn("Failed to delete source branch '{}' after merge (non-critical): {}", sourceBranch, e.getMessage());
        }
    }

    /**
     * Returns the deletion conflicts detected during the most recent merge.
     * Empty if no deletion conflicts were found or if the last merge was
     * successful.
     */
    public List<DeletionConflict> getLastDeletionConflicts() {
        return lastDeletionConflicts;
    }

    /**
     * Returns the update-vs-update conflicts detected during the most recent merge.
     * Empty if no update conflicts were found or if the last merge was
     * successful.
     */
    public List<UpdateConflict> getLastUpdateConflicts() {
        return lastUpdateConflicts;
    }

    /**
     * Returns the aggregated set of conflict owners (normalized author emails)
     * detected via blame during the most recent conflicting merge. Empty if no
     * conflicts were detected or owner detection was unavailable.
     */
    public Set<String> getLastDetectedConflictOwners() {
        return lastDetectedConflictOwners;
    }

    /** Returns the short SHA of the source branch head from the most recent conflicting merge. */
    public String getLastSourceShortSha() {
        return lastSourceShortSha;
    }

    /** Returns the short SHA of the target branch head from the most recent conflicting merge. */
    public String getLastTargetShortSha() {
        return lastTargetShortSha;
    }

    /**
     * Runs blame-based owner detection for the conflicting files and aggregates
     * the per-file owner sets into a single set. Non-fatal: any failure yields
     * an empty set so owner detection never blocks a merge.
     */
    private Set<String> detectConflictOwners(Repository repo, ObjectId sourceCommit,
                                             ObjectId targetCommit, Map<String, int[][]> conflicts) {
        try {
            Map<String, Set<String>> ownersByFile = conflictOwnerResolver.resolveConflictOwners(
                    repo, sourceCommit, targetCommit, conflicts);
            Set<String> owners = new LinkedHashSet<>();
            for (Set<String> fileOwners : ownersByFile.values()) {
                owners.addAll(fileOwners);
            }
            if (!owners.isEmpty()) {
                LOGGER.info("Detected {} conflict owner(s): {}", owners.size(), owners);
            }
            return Set.copyOf(owners);
        } catch (RuntimeException e) {
            LOGGER.debug("Conflict owner detection skipped: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Returns the project-wide severity thresholds loaded from
     * {@code .vitruvius/config/severity-thresholds.json}, or
     * {@link SeverityThresholds#defaults()} when no config file exists.
     */
    public SeverityThresholds getSeverityThresholds() {
        return severityThresholds;
    }

    /**
     * Composes the effective {@link MergePolicy} used during resolution.
     *
     * <p>The caller-supplied policy contributes:
     * <ul>
     *   <li>{@link MergePolicy#getRole() role} — who is approving the merge,</li>
     *   <li>{@link MergePolicy#getDefaultDeletionPolicy() defaultDeletionPolicy} —
     *       the fallback for headless resolution.</li>
     * </ul>
     *
     * <p>{@link MergeManager} substitutes the project-loaded
     * {@link SeverityThresholds} for whatever thresholds the caller's policy
     * carried.  The reason: severity boundaries are a <em>project-wide</em>
     * concern (configured by an admin via the JSON file) and must apply
     * uniformly regardless of which client triggered the merge.  Caller-side
     * thresholds, if any, would otherwise let a misconfigured client lower
     * the bar a role had been granted.
     *
     * <p>If the caller's policy already carries the project-loaded thresholds
     * (the common case after they consulted {@link #getSeverityThresholds()}),
     * the returned policy is functionally equivalent — the substitution is
     * idempotent.
     *
     * @param callerPolicy the policy supplied to {@link #resolveDeletionConflicts}, never null.
     * @return a {@link MergePolicy} with the caller's role + default-policy and the project thresholds.
     */
    public MergePolicy effectivePolicyFor(MergePolicy callerPolicy) {
        Objects.requireNonNull(callerPolicy, "callerPolicy must not be null");
        if (callerPolicy.getSeverityThresholds().equals(severityThresholds)) {
            return callerPolicy;  // already aligned with project config — no allocation needed
        }
        return new MergePolicy(
                callerPolicy.getDefaultDeletionPolicy(),
                callerPolicy.getRole(),
                callerPolicy.getCurrentUserId(),
                severityThresholds);
    }

    /**
     * Installs a domain-specific validator for the auto-resolution tier.
     *
     * <p>The validator is consulted for update conflicts that the origin rule
     * (ORIGINAL-over-CONSEQUENTIAL) cannot resolve automatically. If not set,
     * {@link DomainValidator#NONE} is used and only mixed-origin conflicts are
     * auto-resolved; everything else falls through to manual resolution.
     *
     * @param domainValidator the validator to install, must not be null.
     */
    public void setDomainValidator(DomainValidator domainValidator) {
        this.domainValidator = Objects.requireNonNull(domainValidator,
                "domainValidator must not be null");
    }

    /**
     * Returns {@code true} if the most recent merge detected any semantic
     * conflicts (deletion or update).
     */
    public boolean hasSemanticConflicts() {
        return !lastDeletionConflicts.isEmpty() || !lastUpdateConflicts.isEmpty();
    }

    /**
     * Returns the total number of semantic conflicts detected during
     * the most recent merge.
     */
    public int getSemanticConflictCount() {
        return lastDeletionConflicts.size() + lastUpdateConflicts.size();
    }

    /**
     * Resolves the current deletion conflicts using the given merge policy.
     * This method should be called after {@link #merge(String)} when
     * {@link #getLastDeletionConflicts()} is non-empty.
     *
     * <p>For each conflict resolved with {@link DeletionPolicy#RECOVER_FROM_ANCESTOR},
     * this method physically checks out the affected XMI file from the Git
     * merge-base commit, restoring the deleted model element.  
     *
     * @param mergePolicy   the policy for approval and defaults.
     * @param sourceBranch  the source branch name (for ancestor lookup).
     * @return the list of resolutions applied.
     * @throws BranchOperationException if recovery fails.
     */
    public List<DeletionConflictResolver.Resolution> resolveDeletionConflicts(
            MergePolicy mergePolicy, String sourceBranch) throws BranchOperationException {
        if (lastDeletionConflicts.isEmpty()) {
            return List.of();
        }
        MergePolicy effectivePolicy = effectivePolicyFor(mergePolicy);

        List<DeletionConflict> directResolve = new ArrayList<>();
        List<DeletionConflictResolver.Resolution> resolutions = new ArrayList<>();

        for (DeletionConflict conflict : lastDeletionConflicts) {
            if (isSeniorEscalationResolvable(conflict, effectivePolicy)) {
                directResolve.add(conflict);
            } else if (effectivePolicy.requiresEscalation(conflict)) {
                resolutions.addAll(handleEscalatedConflict(conflict, effectivePolicy));
            } else {
                directResolve.add(conflict);
            }
        }

        if (!directResolve.isEmpty()) {
            DeletionConflictResolver resolver = new DeletionConflictResolver(effectivePolicy);
            resolutions.addAll(resolver.resolve(directResolve));
        }

        // Persist all decisions to the audit log BEFORE any JGit operation modifies the working tree.
        auditDeletionResolutions(resolutions);

        // Collect the conflicts that asked for ancestor recovery.
        List<DeletionConflict> toRecover = resolutions.stream()
                .filter(r -> r.getChosenPolicy() == DeletionPolicy.RECOVER_FROM_ANCESTOR)
                .map(DeletionConflictResolver.Resolution::getConflict)
                .toList();

        if (!toRecover.isEmpty()) {
            try {
                recoverFromAncestor(sourceBranch, toRecover);
            } catch (IOException | GitAPIException e) {
                String uuids = toRecover.stream()
                        .map(DeletionConflict::getDeletedElementUuid)
                        .collect(java.util.stream.Collectors.joining(", "));
                LOGGER.error("Failed to recover {} element(s) from ancestor: {}", toRecover.size(), e.getMessage());
                throw new BranchOperationException("Ancestor recovery failed for " + uuids, e);
            }
        }
        return resolutions;
    }

    /**
     * Runs the clearance-denied escalation path for one conflict: notify owner,
     * build and persist the review package, optionally run an interactive owner
     * session, then check for a recorded owner decision. On deny or no-response
     * timeout, re-routes to a senior role ({@code METHODOLOGIST}).
     */
    private List<DeletionConflictResolver.Resolution> handleEscalatedConflict(
            DeletionConflict conflict, MergePolicy effectivePolicy) {
        try {
            DeletionConflict conflictWithOwners = refreshConflictOwnership(conflict);

            Optional<OwnerEscalation> existingEscalation = ownerEscalationStore.load(
                    conflictWithOwners.getDeletedElementUuid(), lastSourceBranch, lastTargetBranch);
            if (existingEscalation.isPresent() && existingEscalation.get().isEscalatedToSenior()) {
                return seniorBlockedResolution(conflictWithOwners, existingEscalation.get(), effectivePolicy);
            }

            OwnerNotification notification = OwnerNotification.forConflict(
                    conflictWithOwners, severityThresholds, lastSourceBranch, lastTargetBranch);
            String notifiedAt;
            if (!ownerNotifier.notificationExists(notification)) {
                ownerNotifier.notify(notification);
                logAudit(AuditLogEntry.forOwnerNotification(notification, lastSourceBranch, lastTargetBranch));
                notifiedAt = notification.getTimestamp();
                ownerEscalationStore.save(OwnerEscalation.awaitingOwner(
                        conflictWithOwners.getDeletedElementUuid(),
                        lastSourceBranch, lastTargetBranch,
                        List.copyOf(conflictWithOwners.getDetectedOwners()),
                        notifiedAt));
            } else {
                notifiedAt = existingEscalation.flatMap(e -> Optional.ofNullable(e.getNotifiedAt()))
                        .or(() -> ownerNotifier.readNotificationTimestamp(notification))
                        .orElse(notification.getTimestamp());
            }

            ConflictReview review = conflictReviewService.buildReview(
                    conflictWithOwners, severityThresholds, effectivePolicy.getRole(),
                    lastSourceBranch, lastTargetBranch,
                    lastSourceShortSha, lastTargetShortSha);
            if (!conflictReviewService.reviewExists(review, lastSourceBranch, lastTargetBranch)) {
                conflictReviewService.persistReview(review, lastSourceBranch, lastTargetBranch);
                logAudit(AuditLogEntry.forOwnerReview(review, lastSourceBranch, lastTargetBranch));
            }

            ownerEscalationResolver.tryInteractiveDecision(
                    conflictWithOwners, review, effectivePolicy, lastSourceBranch, lastTargetBranch);

            Optional<OwnerDecision> decision = ownerDecisionStore.load(
                    conflictWithOwners.getDeletedElementUuid(), lastSourceBranch, lastTargetBranch);

            if (decision.isPresent() && decision.get().isApproved()) {
                OwnerDecision ownerDecision = decision.get();
                DeletionPolicy chosen = ownerDecision.getChosenPolicy() != null
                        ? ownerDecision.getChosenPolicy()
                        : effectivePolicy.getDefaultDeletionPolicy();
                LOGGER.info("Owner {} approved resolution for element {} with policy {}",
                        ownerDecision.getOwnerId(), conflictWithOwners.getDeletedElementUuid(), chosen);
                logAudit(AuditLogEntry.forOwnerDecision(ownerDecision));
                return List.of(new DeletionConflictResolver.Resolution(
                        conflictWithOwners,
                        chosen,
                        "Owner approved: " + ownerDecision.getOwnerId(),
                        ownerDecision.getRationale()));
            }

            if (decision.isPresent() && !decision.get().isApproved()) {
                OwnerDecision ownerDecision = decision.get();
                escalateToSenior(conflictWithOwners,
                        "Owner denied: " + (ownerDecision.getRationale() != null
                                ? ownerDecision.getRationale() : "no rationale"));
                String blockReason = "Owner denied — escalated to METHODOLOGIST: "
                        + (ownerDecision.getRationale() != null ? ownerDecision.getRationale() : "no rationale");
                LOGGER.warn("MERGE BLOCKED for element {}: {}", conflictWithOwners.getDeletedElementUuid(), blockReason);
                logAudit(AuditLogEntry.forMergeBlocked(
                        conflictWithOwners.getDeletedElementUuid(), blockReason,
                        lastSourceBranch, lastTargetBranch));
                return List.of(new DeletionConflictResolver.Resolution(
                        conflictWithOwners, DeletionPolicy.RESTRICT_DELETIONS, blockReason));
            }

            if (isOwnerNoResponseTimedOut(notifiedAt)) {
                escalateToSenior(conflictWithOwners, "No owner response within timeout");
                String blockReason = "No owner response — escalated to METHODOLOGIST";
                LOGGER.warn("MERGE BLOCKED for element {}: {}", conflictWithOwners.getDeletedElementUuid(), blockReason);
                logAudit(AuditLogEntry.forMergeBlocked(
                        conflictWithOwners.getDeletedElementUuid(), blockReason,
                        lastSourceBranch, lastTargetBranch));
                return List.of(new DeletionConflictResolver.Resolution(
                        conflictWithOwners, DeletionPolicy.RESTRICT_DELETIONS, blockReason));
            }

            String blockReason = "No owner decision recorded — merge blocked until owner responds";
            LOGGER.warn("MERGE BLOCKED for element {}: {}", conflictWithOwners.getDeletedElementUuid(), blockReason);
            logAudit(AuditLogEntry.forMergeBlocked(
                    conflictWithOwners.getDeletedElementUuid(), blockReason,
                    lastSourceBranch, lastTargetBranch));
            return List.of(new DeletionConflictResolver.Resolution(
                    conflictWithOwners, DeletionPolicy.RESTRICT_DELETIONS, blockReason));

        } catch (IOException e) {
            LOGGER.warn("Escalation failed for element {}, treating as blocked: {}",
                    conflict.getDeletedElementUuid(), e.getMessage());
            String reason = "Escalation failed: " + e.getMessage();
            logAudit(AuditLogEntry.forMergeBlocked(
                    conflict.getDeletedElementUuid(), reason, lastSourceBranch, lastTargetBranch));
            return List.of(new DeletionConflictResolver.Resolution(
                    conflict, DeletionPolicy.RESTRICT_DELETIONS, reason));
        }
    }

    private DeletionConflict refreshConflictOwnership(DeletionConflict conflict) throws IOException {
        RoleManager roleManager = new RoleManager(repoRoot);
        Set<String> owners = conflictOwnershipResolver.resolveOwners(
                conflict, lastSourceBranch, lastTargetBranch,
                lastSourceChangelog, lastTargetChangelog,
                lastDetectedConflictOwners, roleManager);
        return conflict.withOwnership(owners, conflict.isOwnerDetectionAvailable());
    }

    private void escalateToSenior(DeletionConflict conflict, String reason) throws IOException {
        RoleManager roleManager = new RoleManager(repoRoot);
        List<String> seniorAssignees = roleManager.findUserIdsByRole("METHODOLOGIST");
        OwnerEscalation escalation = OwnerEscalation.escalatedToSenior(
                conflict.getDeletedElementUuid(), lastSourceBranch, lastTargetBranch,
                reason, seniorAssignees);
        ownerEscalationStore.save(escalation);
        logAudit(AuditLogEntry.forOwnerEscalation(escalation));
        LOGGER.info("Conflict {} escalated to senior role: {}", conflict.getDeletedElementUuid(), seniorAssignees);
    }

    private boolean isSeniorEscalationResolvable(DeletionConflict conflict, MergePolicy effectivePolicy) {
        try {
            Optional<OwnerEscalation> escalation = ownerEscalationStore.load(
                    conflict.getDeletedElementUuid(), lastSourceBranch, lastTargetBranch);
            if (escalation.isEmpty() || !escalation.get().isEscalatedToSenior()) {
                return false;
            }
            return isSeniorAssignee(effectivePolicy, escalation.get());
        } catch (IOException e) {
            LOGGER.debug("Could not load escalation state: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isSeniorAssignee(MergePolicy effectivePolicy, OwnerEscalation escalation) {
        if (effectivePolicy.getCurrentUserId() == null) {
            return false;
        }
        String current = effectivePolicy.getCurrentUserId().toLowerCase();
        if (escalation.getAssignees().stream().anyMatch(a -> a.equalsIgnoreCase(current))) {
            return true;
        }
        return "METHODOLOGIST".equalsIgnoreCase(effectivePolicy.getRoleName());
    }

    private List<DeletionConflictResolver.Resolution> seniorBlockedResolution(
            DeletionConflict conflict, OwnerEscalation escalation, MergePolicy effectivePolicy) {
        String blockReason = "Escalated to METHODOLOGIST — awaiting senior resolution: "
                + escalation.getReason();
        LOGGER.warn("MERGE BLOCKED for element {}: {}", conflict.getDeletedElementUuid(), blockReason);
        logAudit(AuditLogEntry.forMergeBlocked(
                conflict.getDeletedElementUuid(), blockReason, lastSourceBranch, lastTargetBranch));
        return List.of(new DeletionConflictResolver.Resolution(
                conflict, DeletionPolicy.RESTRICT_DELETIONS, blockReason));
    }

    private static boolean isOwnerNoResponseTimedOut(String notifiedAtIso) {
        long timeoutHours = OwnerEscalationConfig.getNoResponseTimeoutHours();
        if (timeoutHours <= 0) {
            return System.console() == null;
        }
        try {
            Instant notifiedAt = Instant.parse(notifiedAtIso);
            return Duration.between(notifiedAt, Instant.now()).toHours() >= timeoutHours;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Records an owner's approve/deny decision on an escalated deletion conflict.
     * The caller must be one of the detected owners (validated via Git user email).
     *
     * @param elementUuid UUID of the conflicting deleted element.
     * @param approve     {@code true} to approve the resolution, {@code false} to deny.
     * @param rationale   optional free-text explanation from the owner.
     * @throws BranchOperationException if the element is not part of the current
     *                                  merge session or the caller is not a detected owner.
     */
    public void submitOwnerDecision(String elementUuid, boolean approve, String rationale)
            throws BranchOperationException {
        submitOwnerDecision(elementUuid, approve, rationale, null);
    }

    /**
     * Records an owner's approve/deny decision on an escalated deletion conflict,
     * optionally including the resolution policy chosen on approve.
     *
     * @param chosenPolicy the owner's chosen {@link DeletionPolicy} when {@code approve}
     *                     is {@code true}; ignored on deny.
     */
    public void submitOwnerDecision(String elementUuid, boolean approve, String rationale,
                                    DeletionPolicy chosenPolicy) throws BranchOperationException {
        Objects.requireNonNull(elementUuid, "elementUuid must not be null");

        DeletionConflict conflict = lastDeletionConflicts.stream()
                .filter(c -> elementUuid.equals(c.getDeletedElementUuid()))
                .findFirst()
                .orElseThrow(() -> new BranchOperationException(
                        "No deletion conflict with element UUID " + elementUuid + " in current merge session"));

        try {
            RoleManager roleManager = new RoleManager(repoRoot);
            String currentUserId = roleManager.getCurrentUserId();
            if (!effectivePolicyFor(MergePolicy.forCurrentUser(roleManager))
                    .isCurrentUserDetectedOwner(conflict.getDetectedOwners())) {
                throw new BranchOperationException(
                        "Current user " + currentUserId + " is not a detected owner of this conflict");
            }

            OwnerDecision decision = OwnerDecision.of(
                    elementUuid, currentUserId, approve, rationale,
                    lastSourceBranch, lastTargetBranch, chosenPolicy);
            ownerDecisionStore.save(decision);
            logAudit(AuditLogEntry.forOwnerDecision(decision));
            flushSessionAuditLog();
            LOGGER.info("Owner decision recorded: {} for element {}", decision.getDecision(), elementUuid);
        } catch (IOException e) {
            throw new BranchOperationException("Failed to save owner decision: " + e.getMessage(), e);
        }
    }

    /** Appends an audit entry when a session logger is active; no-op otherwise. */
    private void logAudit(AuditLogEntry entry) {
        if (sessionAuditLogger != null) {
            sessionAuditLogger.log(entry);
        }
    }

    /**
     * Records the given deletion-conflict resolutions in the current merge
     * session's audit log and flushes it synchronously to disk.
     *
     * <p>No-op when there are no resolutions or when no audit session is active
     * (i.e. resolution was invoked without a preceding conflicting merge).
     */
    private void auditDeletionResolutions(List<DeletionConflictResolver.Resolution> resolutions) {
        if (resolutions.isEmpty() || sessionAuditLogger == null) {
            return;
        }
        for (DeletionConflictResolver.Resolution r : resolutions) {
            sessionAuditLogger.log(AuditLogEntry.forDeletion(r, lastSourceBranch, lastTargetBranch));
        }
        flushSessionAuditLog();
    }

    /**
     * Resolves update-vs-update conflicts using the three-tier auto-resolution
     * strategy of {@link UpdateConflictResolver}.
     *
     * <p>Tier 1 (origin rule) fires first: if one side is ORIGINAL and the
     * other is CONSEQUENTIAL, the ORIGINAL side wins without UI. Tier 2
     * (domain validator) then handles same-origin conflicts where a
     * domain-specific default has been registered via
     * {@link #setDomainValidator(DomainValidator)}. Anything not resolved by
     * either tier is placed in {@link AutoResolutionOutcome#getUnresolved()}
     * for the UI to handle.
     *
     * <p>Auto-resolved decisions are appended to the current merge session's
     * audit log so the trail is identical no matter whether callers invoke this
     * method directly or via {@link #resolveAllConflicts}.
     *
     * @return an outcome describing what was auto-resolved and what still
     *         needs manual intervention.
     */
    public AutoResolutionOutcome resolveUpdateConflicts() {
        AutoResolutionOutcome outcome = new UpdateConflictResolver(domainValidator).resolve(lastUpdateConflicts);
        auditUpdateOutcome(outcome);
        return outcome;
    }

    /**
     * Convenience method that resolves all semantic conflicts (deletion + update)
     * in one call. Runs deletion resolution first (interactive via CLI), then
     * update resolution (automatic via three-tier strategy).
     *
     * @param mergePolicy  the policy for approval and role-based guardrails.
     * @param sourceBranch the source branch name (for ancestor lookup).
     * @throws BranchOperationException if ancestor recovery fails.
     */
    public void resolveAllConflicts(MergePolicy mergePolicy, String sourceBranch)
            throws BranchOperationException {
        // Deletion conflicts — interactive; audit log is flushed synchronously inside before JGit ops
        List<DeletionConflictResolver.Resolution> deletionResolutions =
                resolveDeletionConflicts(mergePolicy, sourceBranch);
        LOGGER.info("Resolved {} deletion conflict(s)", deletionResolutions.size());

        // Update conflicts — fully automatic (three-tier strategy). resolveUpdateConflicts()
        // records its own decisions into the same session audit log opened above.
        AutoResolutionOutcome updateOutcome = resolveUpdateConflicts();
        LOGGER.info("Update conflicts: {}/{} auto-resolved, {} require manual input",
                updateOutcome.getAutoResolved().size(),
                updateOutcome.totalCount(),
                updateOutcome.getUnresolved().size());
    }

    /**
     * Records the auto-resolved update decisions in the current merge session's
     * audit log and flushes it synchronously to disk.
     *
     * <p>No-op when nothing was auto-resolved or when no audit session is active.
     */
    private void auditUpdateOutcome(AutoResolutionOutcome outcome) {
        if (outcome.getAutoResolved().isEmpty() || sessionAuditLogger == null) {
            return;
        }
        for (AutoResolutionOutcome.ResolvedConflict resolved : outcome.getAutoResolved()) {
            sessionAuditLogger.log(AuditLogEntry.forUpdateAutoResolved(resolved, lastSourceBranch, lastTargetBranch));
        }
        flushSessionAuditLog();
    }

    /**
     * Flushes the current merge session's audit log to disk. The underlying
     * {@link AuditLogger#flush()} rewrites the complete entry set, so calling
     * this after the deletion pass and again after the update pass yields a
     * single file containing both. Non-fatal: failures are logged, not thrown,
     * so an audit problem never blocks the merge itself.
     */
    private void flushSessionAuditLog() {
        try {
            Path auditPath = sessionAuditLogger.flush();
            LOGGER.info("Audit log written: {} ({} decision(s) recorded)",
                    auditPath.getFileName(), sessionAuditLogger.size());
        } catch (IOException e) {
            LOGGER.warn("Failed to flush audit log (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Physically recovers deleted model elements by checking out the
     * {@code .vitruvius/vsum} directory from the merge-base (common ancestor)
     * commit. One checkout restores state for every conflict in the list, so
     * recovery for N conflicts costs one JGit operation rather than N.
     */
    private void recoverFromAncestor(String sourceBranch, List<DeletionConflict> conflicts)
            throws IOException, GitAPIException {
        if (conflicts.isEmpty()) {
            return;
        }
        try (Git git = Git.open(repoRoot.toFile())) {
            Repository repo = git.getRepository();

            // Find merge-base between HEAD and source branch
            ObjectId headId = repo.resolve("HEAD");
            Ref sourceRef = repo.findRef("refs/heads/" + sourceBranch);
            if (sourceRef == null) {
                throw new IOException("Source branch not found: " + sourceBranch);
            }
            ObjectId sourceId = sourceRef.getObjectId();

            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(headId));
                walk.markStart(walk.parseCommit(sourceId));
                RevCommit ancestor = walk.next();

                if (ancestor == null) {
                    LOGGER.warn("No common ancestor found between HEAD and '{}'. Cannot recover {} element(s)",
                            sourceBranch, conflicts.size());
                    return;
                }

                String ancestorSha = ancestor.getName();
                LOGGER.info("Recovering vsum state from ancestor {} for {} element(s)",
                        ancestorSha.substring(0, 7), conflicts.size());

                git.checkout()
                        .setStartPoint(ancestorSha)
                        .addPath(".vitruvius/vsum")
                        .call();

                LOGGER.info("Successfully recovered vsum state from ancestor {} for {} conflict(s)",
                        ancestorSha.substring(0, 7), conflicts.size());
            }
        }
    }
}