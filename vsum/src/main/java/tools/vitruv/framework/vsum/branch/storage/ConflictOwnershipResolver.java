package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangelogManager.ChangelogDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves per-conflict owners for the clearance-denied path.
 *
 * <p>Primary signal: commit author of the branch that recorded
 * {@link ChangeOrigin#ORIGINAL} changes among the conflict's affected updates
 * (diagram: "Author of Affected Original Changes"). When no semantic owner is
 * found, falls back to merge-wide blame authors, then to a senior-role fallback
 * assignee via {@link RoleManager}.
 */
public class ConflictOwnershipResolver {

    private static final Logger LOGGER = LogManager.getLogger(ConflictOwnershipResolver.class);

    /**
     * Resolves the owner set for a single deletion conflict.
     *
     * @param conflict            the conflict to attribute.
     * @param sourceBranch        merge source branch name.
     * @param targetBranch        merge target branch name.
     * @param sourceChangelog     changelog for the source branch head, may be null.
     * @param targetChangelog     changelog for the target branch head, may be null.
     * @param blameFallbackOwners merge-wide blame owners (may be empty).
     * @param roleManager         role configuration for fallback assignees.
     * @return normalized owner emails, never null.
     */
    public Set<String> resolveOwners(DeletionConflict conflict,
                                     String sourceBranch, String targetBranch,
                                     ChangelogDocument sourceChangelog,
                                     ChangelogDocument targetChangelog,
                                     Set<String> blameFallbackOwners,
                                     RoleManager roleManager) {
        Objects.requireNonNull(conflict, "conflict must not be null");

        Set<String> owners = new LinkedHashSet<>();

        boolean hasOriginalUpdates = conflict.getAffectedUpdates().stream()
                .anyMatch(e -> e.getOrigin() == ChangeOrigin.ORIGINAL);
        if (hasOriginalUpdates) {
            ChangelogDocument updatingChangelog = changelogForBranch(
                    conflict.getUpdatingBranch(), sourceBranch, targetBranch,
                    sourceChangelog, targetChangelog);
            addCommitAuthor(owners, updatingChangelog,
                    "ORIGINAL updates on branch '" + conflict.getUpdatingBranch() + "'");
        }

        if (conflict.getDeletionOrigin() == ChangeOrigin.ORIGINAL) {
            ChangelogDocument deletingChangelog = changelogForBranch(
                    conflict.getDeletingBranch(), sourceBranch, targetBranch,
                    sourceChangelog, targetChangelog);
            addCommitAuthor(owners, deletingChangelog,
                    "ORIGINAL deletion on branch '" + conflict.getDeletingBranch() + "'");
        }

        if (owners.isEmpty() && blameFallbackOwners != null && !blameFallbackOwners.isEmpty()) {
            owners.addAll(blameFallbackOwners);
            LOGGER.debug("Conflict {}: using blame fallback owner(s): {}",
                    conflict.getDeletedElementUuid(), blameFallbackOwners);
        }

        if (owners.isEmpty() && roleManager != null) {
            List<String> fallback = roleManager.findFallbackOwnerIds();
            owners.addAll(fallback);
            if (!fallback.isEmpty()) {
                LOGGER.info("Conflict {}: no semantic/blame owner; assigned fallback: {}",
                        conflict.getDeletedElementUuid(), fallback);
            }
        }

        return Set.copyOf(owners);
    }

    private static ChangelogDocument changelogForBranch(String branchName,
                                                          String sourceBranch, String targetBranch,
                                                          ChangelogDocument sourceChangelog,
                                                          ChangelogDocument targetChangelog) {
        if (Objects.equals(branchName, sourceBranch)) {
            return sourceChangelog;
        }
        if (Objects.equals(branchName, targetBranch)) {
            return targetChangelog;
        }
        return null;
    }

    private static void addCommitAuthor(Set<String> owners, ChangelogDocument changelog, String context) {
        if (changelog == null || changelog.commit == null || changelog.commit.author == null) {
            return;
        }
        String email = changelog.commit.author.email;
        if (email == null || email.isBlank()) {
            return;
        }
        String normalized = email.trim().toLowerCase();
        owners.add(normalized);
        LOGGER.debug("Conflict owner from {}: {}", context, normalized);
    }
}
