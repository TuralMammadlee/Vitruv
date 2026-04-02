package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MergePolicy}.
 */
class MergePolicyTest {

    @Test
    @DisplayName("Junior cannot approve high-impact deletions")
    void juniorCannotApproveHighImpact() {
        MergePolicy policy = MergePolicy.forRole(UserRole.JUNIOR);
        DeletionConflict conflict = conflictWithUpdates(5); // 5 lost updates >= threshold of 3

        assertFalse(policy.canApproveDeletion(conflict));
        assertTrue(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Junior can approve low-impact deletions")
    void juniorCanApproveLowImpact() {
        MergePolicy policy = MergePolicy.forRole(UserRole.JUNIOR);
        DeletionConflict conflict = conflictWithUpdates(2); // 2 lost updates < threshold of 3

        assertTrue(policy.canApproveDeletion(conflict));
        assertFalse(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Senior can approve any deletion")
    void seniorCanApproveAny() {
        MergePolicy policy = MergePolicy.forRole(UserRole.SENIOR);
        DeletionConflict conflict = conflictWithUpdates(10);

        assertTrue(policy.canApproveDeletion(conflict));
        assertFalse(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Architect can approve any deletion")
    void architectCanApproveAny() {
        MergePolicy policy = MergePolicy.forRole(UserRole.ARCHITECT);
        DeletionConflict conflict = conflictWithUpdates(100);

        assertTrue(policy.canApproveDeletion(conflict));
        assertFalse(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Custom threshold is respected")
    void customThresholdIsRespected() {
        MergePolicy policy = new MergePolicy(
                DeletionPolicy.RECOVER_FROM_ANCESTOR, UserRole.JUNIOR, 5);

        // 4 updates < threshold of 5 -> junior can approve
        assertTrue(policy.canApproveDeletion(conflictWithUpdates(4)));
        // 5 updates >= threshold of 5 -> junior cannot approve
        assertFalse(policy.canApproveDeletion(conflictWithUpdates(5)));
    }

    @Test
    @DisplayName("Invalid threshold throws exception")
    void invalidThresholdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, UserRole.JUNIOR, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, UserRole.JUNIOR, -1));
    }

    @Test
    @DisplayName("Default policy is RECOVER_FROM_ANCESTOR")
    void defaultPolicyIsRecoverFromAncestor() {
        MergePolicy policy = MergePolicy.forRole(UserRole.SENIOR);
        assertEquals(DeletionPolicy.RECOVER_FROM_ANCESTOR, policy.getDefaultDeletionPolicy());
    }

    /**
     * Creates a DeletionConflict with the given number of affected updates.
     */
    private DeletionConflict conflictWithUpdates(int updateCount) {
        List<SemanticChangeEntry> updates = new java.util.ArrayList<>();
        for (int i = 0; i < updateCount; i++) {
            updates.add(SemanticChangeEntry.builder()
                    .index(i)
                    .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                    .emfType("ReplaceSingleValuedEAttribute")
                    .elementUuid("uuid-" + i)
                    .origin(ChangeOrigin.ORIGINAL)
                    .build());
        }
        return new DeletionConflict(
                "deleted-uuid", "entities::Entity",
                "feature-branch", "main",
                updates, true, ChangeOrigin.ORIGINAL);
    }
}
