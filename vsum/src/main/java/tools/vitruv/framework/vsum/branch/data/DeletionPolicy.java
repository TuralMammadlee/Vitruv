package tools.vitruv.framework.vsum.branch.data;

/**
 * Defines the available strategies for resolving delete-vs-update conflicts
 * during a branch merge.
 *
 * <p>The choice of policy can be configured per V-SUM and may differ based on
 * the type of model, the correspondence that is touched, or the user's role.
 *
 * <p>Corresponds to the three alternatives described in the supervisor's email:
 * <ol>
 *   <li>Recover from shared ancestor</li>
 *   <li>Accept deletion with a warning (tombstoning)</li>
 *   <li>Restrict deletions entirely</li>
 * </ol>
 *
 * @see DeletionConflict
 * @see MergePolicy
 */
public enum DeletionPolicy {

    /**
     * If a shared Git ancestor exists, recover every item within the deleted
     * instance from the ancestor, effectively cancelling the deletion in
     * favour of preserving the other branch's updates.
     */
    RECOVER_FROM_ANCESTOR("Recover deleted items from shared ancestor"),

    /**
     * Accept the deletion but warn the user about the updates that will be
     * lost.  This is the tombstoning fallback when recovery is not possible.
     */
    TOMBSTONE_WITH_WARNING("Accept deletion with a warning about lost updates"),

    /**
     * Block the deletion entirely.  Useful in strict environments where
     * deletions of certain model element types are not permitted without
     * explicit escalation.
     */
    RESTRICT_DELETIONS("Block this deletion (requires manual resolution)");

    private final String description;

    DeletionPolicy(String description) {
        this.description = description;
    }

    /**
     * Returns a human-readable description of this policy.
     */
    public String getDescription() {
        return description;
    }
}
