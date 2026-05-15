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
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.ModelMergeResult;
import tools.vitruv.framework.vsum.branch.data.SeverityThresholds;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.data.ValidationResult;
import tools.vitruv.framework.vsum.branch.exception.BranchOperationException;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictAnalyzer;
import tools.vitruv.framework.vsum.branch.storage.DeletionConflictResolver;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager;
import tools.vitruv.framework.vsum.branch.storage.UpdateConflictAnalyzer;
import tools.vitruv.framework.vsum.branch.storage.UpdateConflictResolver;
import tools.vitruv.framework.vsum.branch.util.MergeResultFile;
import tools.vitruv.framework.vsum.branch.util.MergeTriggerFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** Deletion conflicts detected during the most recent merge (empty if none). */
    private List<DeletionConflict> lastDeletionConflicts = List.of();

    /** Update-vs-update conflicts detected during the most recent merge (empty if none). */
    private List<UpdateConflict> lastUpdateConflicts = List.of();

    /** Configurable severity thresholds loaded from config, or defaults. */
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
        loadSeverityThresholds();
    }

    /**
     * Loads severity thresholds from the config directory.
     * Falls back to defaults if the file doesn't exist or is invalid.
     */
    private void loadSeverityThresholds() {
        try {
            Path configDir = repoRoot.resolve(".vitruvius").resolve("config");
            this.severityThresholds = SeverityThresholds.load(configDir);
            LOGGER.debug("Severity thresholds loaded: {}", severityThresholds);
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

                // Conflict classification and severity are handled by UpdateConflict tags
                // (OriginPermutation + FundamentalConflictType). Auto-resolution is done
                // via resolveUpdateConflicts() after the caller inspects this result.
                List<String> conflictingFiles = new ArrayList<>(conflicts.keySet());
                LOGGER.warn("Merge resulted in {} conflict(s): {}", conflictingFiles.size(), conflictingFiles);

                // Analyze changelogs for semantic conflicts
                try {
                    Ref resolvedSourceRef = repo.findRef("refs/heads/" + sourceBranch);
                    String shortShaSource = resolvedSourceRef.getObjectId().abbreviate(7).name();
                    String shortShaTarget = repo.resolve("HEAD").abbreviate(7).name();
                    var sourceChangelog = changelogManager.read(sourceBranch, shortShaSource);
                    var targetChangelog = changelogManager.read(targetBranch, shortShaTarget);

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
                } catch (Exception e) {
                    LOGGER.debug("Semantic conflict analysis skipped: {}", e.getMessage());
                    this.lastDeletionConflicts = List.of();
                    this.lastUpdateConflicts = List.of();
                }

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
     * Returns the severity thresholds currently in use.
     */
    public SeverityThresholds getSeverityThresholds() {
        return severityThresholds;
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
        DeletionConflictResolver resolver = new DeletionConflictResolver(mergePolicy);
        List<DeletionConflictResolver.Resolution> resolutions = resolver.resolve(lastDeletionConflicts);

        // Execute physical recovery for RECOVER_FROM_ANCESTOR resolutions
        for (DeletionConflictResolver.Resolution resolution : resolutions) {
            if (resolution.getChosenPolicy() == DeletionPolicy.RECOVER_FROM_ANCESTOR) {
                try {
                    recoverFromAncestor(sourceBranch, resolution.getConflict());
                } catch (Exception e) {
                    LOGGER.error("Failed to recover element '{}' from ancestor: {}",
                            resolution.getConflict().getDeletedElementUuid(), e.getMessage());
                    throw new BranchOperationException(
                            "Ancestor recovery failed for " + resolution.getConflict().getDeletedElementUuid(), e);
                }
            }
        }
        return resolutions;
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
     * @return an outcome describing what was auto-resolved and what still
     *         needs manual intervention.
     */
    public AutoResolutionOutcome resolveUpdateConflicts() {
        return new UpdateConflictResolver(domainValidator).resolve(lastUpdateConflicts);
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
        List<DeletionConflictResolver.Resolution> deletionResolutions =
                resolveDeletionConflicts(mergePolicy, sourceBranch);
        LOGGER.info("Resolved {} deletion conflict(s)", deletionResolutions.size());

        AutoResolutionOutcome updateOutcome = resolveUpdateConflicts();
        LOGGER.info("Update conflicts: {}/{} auto-resolved, {} require manual input",
                updateOutcome.getAutoResolved().size(),
                updateOutcome.totalCount(),
                updateOutcome.getUnresolved().size());
    }

    /**
     * Physically recovers a deleted model element by checking out its file
     * from the merge-base (common ancestor) commit.
     *
     * <p>Uses JGit to:
     * <ol>
     *   <li>Find the merge-base between HEAD and the source branch</li>
     *   <li>Identify which XMI file contained the deleted element</li>
     *   <li>Checkout that file from the ancestor commit into the working tree</li>
     * </ol>
     */
    private void recoverFromAncestor(String sourceBranch, DeletionConflict conflict)
            throws IOException, GitAPIException {
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
                    LOGGER.warn("No common ancestor found between HEAD and '{}'. " +
                            "Cannot recover element '{}'", sourceBranch,
                            conflict.getDeletedElementUuid());
                    return;
                }

                String ancestorSha = ancestor.getName();
                LOGGER.info("Recovering from ancestor {} for element '{}'",
                        ancestorSha.substring(0, 7), conflict.getDeletedElementUuid());

                // Find conflicting XMI files from the deletion conflict's affected updates.
                // The affected updates reference elements in specific files; we recover
                // the .xmi files that were part of the Git-level conflict.
                // Use the changelog path pattern to identify relevant files.
                git.checkout()
                        .setStartPoint(ancestorSha)
                        .addPath(".vitrivius/vsum")  // Recover the entire vsum directory from ancestor
                        .call();

                LOGGER.info("Successfully recovered vsum state from ancestor {} " +
                        "for conflict on element '{}'",
                        ancestorSha.substring(0, 7), conflict.getDeletedElementUuid());
            }
        }
    }
}