package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MergePolicy} with the dynamic role system.
 */
class MergePolicyTest {

    @Test
    @DisplayName("Developer cannot approve HIGH-severity deletions")
    void developerCannotApproveHighSeverity() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
        DeletionConflict conflict = conflictWithUpdates(5); // HIGH severity (3-9)

        assertFalse(policy.canApproveDeletion(conflict));
        assertTrue(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Developer can approve MEDIUM-severity deletions within update limit")
    void developerCanApproveMediumSeverity() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
        DeletionConflict conflict = conflictWithUpdates(2); // MEDIUM severity, under limit of 3

        assertTrue(policy.canApproveDeletion(conflict));
        assertFalse(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Developer blocked when update count exceeds limit")
    void developerBlockedAboveUpdateLimit() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
        // Developer maxLostUpdates is 3 (inclusive). 4 updates exceeds the cap.
        DeletionConflict conflict = conflictWithUpdates(4); // HIGH severity (>= highThreshold)

        assertFalse(policy.canApproveDeletion(conflict));
    }

    @Test
    @DisplayName("Methodologist can approve any deletion")
    void methodologistCanApproveAny() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.methodologist());
        DeletionConflict conflict = conflictWithUpdates(100); // CRITICAL severity

        assertTrue(policy.canApproveDeletion(conflict));
        assertFalse(policy.requiresEscalation(conflict));
    }

    @Test
    @DisplayName("Detected owner can approve even when role limits would block")
    void detectedOwnerCanApproveBeyondRoleLimits() {
        MergePolicy policy = new MergePolicy(
                DeletionPolicy.RECOVER_FROM_ANCESTOR,
                RoleDefinition.developer(),
                "owner@example.com",
                SeverityThresholds.defaults());

        // 10 CONSEQUENTIAL updates → CRITICAL severity, normally blocked for a developer.
        DeletionConflict conflict = conflictWithUpdates(10)
                .withOwnership(Set.of("owner@example.com"), true);

        assertTrue(policy.canApproveDeletion(conflict));
    }

    @Test
    @DisplayName("Non-owner falls back to role clearance")
    void nonOwnerFallsBackToRoleClearance() {
        MergePolicy policy = new MergePolicy(
                DeletionPolicy.RECOVER_FROM_ANCESTOR,
                RoleDefinition.developer(),
                "other@example.com",
                SeverityThresholds.defaults());

        DeletionConflict conflict = conflictWithUpdates(5)
                .withOwnership(Set.of("owner@example.com"), true);

        assertFalse(policy.canApproveDeletion(conflict));
    }

    @Test
    @DisplayName("Missing owner detection keeps legacy role behavior")
    void missingOwnerDetectionUsesRoleBehavior() {
        MergePolicy policy = new MergePolicy(
                DeletionPolicy.RECOVER_FROM_ANCESTOR,
                RoleDefinition.developer(),
                "dev@example.com",
                SeverityThresholds.defaults());

        DeletionConflict conflict = conflictWithUpdates(2)
                .withOwnership(Set.of(), true);

        assertTrue(policy.canApproveDeletion(conflict));
    }

    @Test
    @DisplayName("Custom role with HIGH max severity can approve HIGH conflicts")
    void customRoleWithHighSeverity() {
        RoleDefinition seniorDev = new RoleDefinition(
                "SENIOR_DEV", 7, 10, ConflictSeverity.HIGH, "Senior developer", false);
        MergePolicy policy = MergePolicy.forRole(seniorDev);

        // 5 updates = HIGH severity, and maxLostUpdates is 10 → should be allowed
        assertTrue(policy.canApproveDeletion(conflictWithUpdates(5)));
        // 9 updates = HIGH severity, still within limit
        assertTrue(policy.canApproveDeletion(conflictWithUpdates(9)));
        // 10 updates = CRITICAL severity → blocked (max severity is HIGH)
        assertFalse(policy.canApproveDeletion(conflictWithUpdates(10)));
    }

    @Test
    @DisplayName("Custom role blocked when update count exceeds limit even if severity is OK")
    void customRoleBlockedByUpdateLimit() {
        RoleDefinition limited = new RoleDefinition(
                "LIMITED", 5, 5, ConflictSeverity.HIGH, "Limited role", false);
        MergePolicy policy = MergePolicy.forRole(limited);

        // 4 updates (HIGH severity) + under limit → allowed
        assertTrue(policy.canApproveDeletion(conflictWithUpdates(4)));
        // 5 updates (HIGH severity) + at inclusive cap → allowed
        assertTrue(policy.canApproveDeletion(conflictWithUpdates(5)));
        // 6 updates (HIGH severity) + over cap → blocked by update count
        assertFalse(policy.canApproveDeletion(conflictWithUpdates(6)));
    }

    @Test
    @DisplayName("Default policy is RECOVER_FROM_ANCESTOR")
    void defaultPolicyIsRecoverFromAncestor() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
        assertEquals(DeletionPolicy.RECOVER_FROM_ANCESTOR, policy.getDefaultDeletionPolicy());
    }

    @Test
    @DisplayName("getRoleName returns the role's name")
    void getRoleName() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
        assertEquals("DEVELOPER", policy.getRoleName());
    }

    @Test
    @DisplayName("Null conflict throws NullPointerException")
    void nullConflictThrows() {
        MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
        assertThrows(NullPointerException.class, () -> policy.canApproveDeletion(null));
        assertThrows(NullPointerException.class, () -> policy.requiresEscalation(null));
    }

    @Test
    @DisplayName("Null role in constructor throws NullPointerException")
    void nullRoleThrows() {
        assertThrows(NullPointerException.class, () ->
                new MergePolicy(DeletionPolicy.RECOVER_FROM_ANCESTOR, null));
    }

    /**
     * Creates a DeletionConflict with the given number of affected updates.
     *
     * <p>The affected updates default to {@link ChangeOrigin#CONSEQUENTIAL} so
     * that the weighted-impact score equals {@code updateCount} and these
     * routing tests can be expressed in terms of raw counts. Tests that need
     * to exercise the origin-weighted formula directly live in
     * {@code DeletionConflictTest}.
     */
    private DeletionConflict conflictWithUpdates(int updateCount) {
        List<SemanticChangeEntry> updates = new java.util.ArrayList<>();
        for (int i = 0; i < updateCount; i++) {
            updates.add(SemanticChangeEntry.builder()
                    .index(i)
                    .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                    .emfType("ReplaceSingleValuedEAttribute")
                    .elementUuid("uuid-" + i)
                    .origin(ChangeOrigin.CONSEQUENTIAL)
                    .build());
        }
        return new DeletionConflict(
                "deleted-uuid", "entities::Entity",
                "feature-branch", "main",
                updates, true, ChangeOrigin.ORIGINAL);
    }
}
