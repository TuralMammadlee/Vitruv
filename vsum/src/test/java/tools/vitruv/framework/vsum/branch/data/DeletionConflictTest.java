package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeletionConflict}, focused on the origin-weighted
 * impact formula and how its result routes through {@link MergePolicy} to
 * the role-based clearance check on {@link RoleDefinition}.
 */
class DeletionConflictTest {

    // ── Weighted impact formula ──────────────────────────────────────────────

    @Nested
    @DisplayName("getWeightedImpact()")
    class WeightedImpact {

        @Test
        @DisplayName("empty affected-updates list returns score 0")
        void emptyListIsZero() {
            assertEquals(0, conflict().getWeightedImpact());
        }

        @Test
        @DisplayName("single CONSEQUENTIAL update contributes weight 1")
        void singleConsequential() {
            assertEquals(1, conflict(ChangeOrigin.CONSEQUENTIAL).getWeightedImpact());
        }

        @Test
        @DisplayName("single ORIGINAL update contributes weight 2")
        void singleOriginal() {
            assertEquals(2, conflict(ChangeOrigin.ORIGINAL).getWeightedImpact());
        }

        @Test
        @DisplayName("UNKNOWN origin is treated like CONSEQUENTIAL")
        void unknownIsLikeConsequential() {
            assertEquals(1, conflict(ChangeOrigin.UNKNOWN).getWeightedImpact());
        }

        @Test
        @DisplayName("mixed origins sum correctly")
        void mixedOrigins() {
            // 2 ORIGINAL (4) + 3 CONSEQUENTIAL (3) + 1 UNKNOWN (1) = 8
            DeletionConflict c = conflict(
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL,
                    ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL,
                    ChangeOrigin.UNKNOWN);
            assertEquals(8, c.getWeightedImpact());
        }

        @Test
        @DisplayName("raw lost-update count is independent of weighting")
        void rawCountStillReturnsListSize() {
            DeletionConflict c = conflict(
                    ChangeOrigin.ORIGINAL, ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL);
            assertEquals(3, c.getLostUpdateCount());
            assertEquals(4, c.getWeightedImpact());
        }
    }

    // ── Severity is derived from weighted impact ─────────────────────────────

    @Nested
    @DisplayName("getSeverity() uses weighted impact")
    class SeverityFromWeightedImpact {

        @Test
        @DisplayName("two ORIGINAL edits (impact 4) escalate to HIGH while two CONSEQUENTIAL stay MEDIUM")
        void originalsEscalateBeyondCount() {
            DeletionConflict twoOriginal = conflict(ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);
            DeletionConflict twoConsequential = conflict(ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL);

            assertEquals(ConflictSeverity.HIGH,   twoOriginal.getSeverity());
            assertEquals(ConflictSeverity.MEDIUM, twoConsequential.getSeverity());
        }

        @Test
        @DisplayName("five ORIGINAL edits (impact 10) reach CRITICAL")
        void manyOriginalsReachCritical() {
            DeletionConflict c = conflict(
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL,
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);
            assertEquals(ConflictSeverity.CRITICAL, c.getSeverity());
        }

        @Test
        @DisplayName("custom thresholds shift the routing boundaries")
        void customThresholdsApply() {
            // Six ORIGINAL edits → impact 12.
            // Default thresholds (1/3/10): impact 12 → CRITICAL.
            // Custom thresholds (5/20/50): impact 12 → MEDIUM.
            SeverityThresholds custom = new SeverityThresholds(5, 20, 50);
            DeletionConflict c = conflict(
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL,
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);
            assertEquals(ConflictSeverity.CRITICAL, c.getSeverity());
            assertEquals(ConflictSeverity.MEDIUM,   c.getSeverity(custom));
        }
    }

    // ── Routing into the role-based clearance check ─────────────────────────

    @Nested
    @DisplayName("Severity routes correctly to RoleDefinition")
    class RoleClearanceRouting {

        @Test
        @DisplayName("Developer (max=MEDIUM) is blocked by a single ORIGINAL edit pair")
        void developerBlockedByOriginalEdits() {
            // Two ORIGINAL edits → impact 4 → HIGH; developer.maxSeverity = MEDIUM
            MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
            DeletionConflict c = conflict(ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);

            assertEquals(ConflictSeverity.HIGH, c.getSeverity());
            assertFalse(policy.canApproveDeletion(c));
            assertTrue(policy.requiresEscalation(c));
        }

        @Test
        @DisplayName("Same lost-update count of CONSEQUENTIAL edits stays approvable for developer")
        void developerApprovesConsequentialEdits() {
            // Two CONSEQUENTIAL edits → impact 2 → MEDIUM; under developer's update cap
            MergePolicy policy = MergePolicy.forRole(RoleDefinition.developer());
            DeletionConflict c = conflict(ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL);

            assertEquals(ConflictSeverity.MEDIUM, c.getSeverity());
            assertTrue(policy.canApproveDeletion(c));
            assertFalse(policy.requiresEscalation(c));
        }

        @Test
        @DisplayName("Methodologist clears any weighted severity")
        void methodologistClearsAnyImpact() {
            MergePolicy policy = MergePolicy.forRole(RoleDefinition.methodologist());
            DeletionConflict c = conflict(
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL,
                    ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);

            assertEquals(ConflictSeverity.CRITICAL, c.getSeverity());
            assertTrue(policy.canApproveDeletion(c));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DeletionConflict conflict(ChangeOrigin... updateOrigins) {
        List<SemanticChangeEntry> updates = new ArrayList<>();
        int idx = 0;
        for (ChangeOrigin origin : updateOrigins) {
            updates.add(SemanticChangeEntry.builder()
                    .index(idx++)
                    .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                    .emfType("ReplaceSingleValuedEAttribute")
                    .elementUuid("uuid-" + idx)
                    .origin(origin)
                    .build());
        }
        return new DeletionConflict(
                "deleted-uuid", "entities::Entity",
                "feature", "main",
                updates, true, ChangeOrigin.ORIGINAL);
    }
}
