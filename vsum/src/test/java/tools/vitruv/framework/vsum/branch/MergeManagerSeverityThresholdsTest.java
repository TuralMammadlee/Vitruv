package tools.vitruv.framework.vsum.branch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.data.SeverityThresholds;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.initRepo;

/**
 * Unit tests for the severity-thresholds wiring on {@link MergeManager}.
 *
 * <p>Verifies the two-step pipeline that delivers admin-configurable severity
 * boundaries to the conflict resolver:
 * <ol>
 *   <li>Constructor loads {@code .vitruvius/config/severity-thresholds.json}
 *       into {@link MergeManager#getSeverityThresholds()}.</li>
 *   <li>{@link MergeManager#effectivePolicyFor(MergePolicy)} substitutes those
 *       project thresholds into any caller-supplied policy, preserving the
 *       caller's role and default-deletion preference.</li>
 * </ol>
 *
 * <p>The end-to-end behavioural test lives in the integration package; this
 * class focuses on the two narrow guarantees above so wiring regressions are
 * caught with a fast unit-level signal.
 */
class MergeManagerSeverityThresholdsTest {

    @Nested
    @DisplayName("config loading")
    class ConfigLoading {

        @Test
        @DisplayName("uses defaults when no config file is present")
        void defaultsWhenNoConfig(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                MergeManager manager = new MergeManager(repoDir);
                assertEquals(SeverityThresholds.defaults(), manager.getSeverityThresholds());
            }
        }

        @Test
        @DisplayName("loads custom thresholds from .vitruvius/config/severity-thresholds.json")
        void loadsCustomThresholds(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                Path configDir = repoDir.resolve(".vitruvius").resolve("config");
                new SeverityThresholds(5, 20, 50).save(configDir);

                MergeManager manager = new MergeManager(repoDir);
                SeverityThresholds loaded = manager.getSeverityThresholds();

                assertEquals(5,  loaded.getMediumThreshold());
                assertEquals(20, loaded.getHighThreshold());
                assertEquals(50, loaded.getCriticalThreshold());
            }
        }

        @Test
        @DisplayName("falls back to defaults when config file is corrupt")
        void fallsBackOnCorruptConfig(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                Path configDir = repoDir.resolve(".vitruvius").resolve("config");
                Files.createDirectories(configDir);
                // Violates the strict-increase invariant — load() throws IOException, MergeManager swallows it
                Files.writeString(configDir.resolve("severity-thresholds.json"),
                        "{\"mediumThreshold\":10,\"highThreshold\":3,\"criticalThreshold\":50}");

                MergeManager manager = new MergeManager(repoDir);
                assertEquals(SeverityThresholds.defaults(), manager.getSeverityThresholds(),
                        "corrupt config must not break construction — defaults apply");
            }
        }
    }

    @Nested
    @DisplayName("effectivePolicyFor()")
    class EffectivePolicy {

        @Test
        @DisplayName("substitutes project thresholds for caller-supplied ones")
        void projectThresholdsTakePrecedence(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                new SeverityThresholds(5, 20, 50).save(repoDir.resolve(".vitruvius").resolve("config"));
                MergeManager manager = new MergeManager(repoDir);

                // Caller passes a policy with the default thresholds (1/3/10)
                MergePolicy caller = MergePolicy.forRole(RoleDefinition.developer());
                assertEquals(SeverityThresholds.defaults(), caller.getSeverityThresholds());

                MergePolicy effective = manager.effectivePolicyFor(caller);

                // Caller's role and default-policy are preserved
                assertEquals(caller.getRole(),                  effective.getRole());
                assertEquals(caller.getDefaultDeletionPolicy(), effective.getDefaultDeletionPolicy());
                // Thresholds were swapped for the project-loaded values
                assertEquals(manager.getSeverityThresholds(),   effective.getSeverityThresholds());
                assertNotEquals(caller.getSeverityThresholds(), effective.getSeverityThresholds());
            }
        }

        @Test
        @DisplayName("returns the same instance when caller's thresholds already match project config")
        void idempotentWhenAlreadyAligned(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                MergeManager manager = new MergeManager(repoDir);
                // No config file → both sides hold SeverityThresholds.defaults()
                MergePolicy caller = MergePolicy.forRole(RoleDefinition.methodologist());

                MergePolicy effective = manager.effectivePolicyFor(caller);
                assertSame(caller, effective,
                        "no substitution needed when thresholds already match — avoid the allocation");
            }
        }

        @Test
        @DisplayName("rejects null input")
        void rejectsNullInput(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                MergeManager manager = new MergeManager(repoDir);
                assertThrows(NullPointerException.class, () -> manager.effectivePolicyFor(null));
            }
        }

        @Test
        @DisplayName("preserves caller's non-default DeletionPolicy preference")
        void preservesCallerDefaultPolicy(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                new SeverityThresholds(5, 20, 50).save(repoDir.resolve(".vitruvius").resolve("config"));
                MergeManager manager = new MergeManager(repoDir);

                MergePolicy caller = new MergePolicy(
                        DeletionPolicy.TOMBSTONE_WITH_WARNING,
                        RoleDefinition.developer());

                MergePolicy effective = manager.effectivePolicyFor(caller);
                assertEquals(DeletionPolicy.TOMBSTONE_WITH_WARNING, effective.getDefaultDeletionPolicy(),
                        "caller's choice of default policy must survive the substitution");
            }
        }
    }

    @Nested
    @DisplayName("Wiring actually changes approval decisions")
    class WiringChangesDecisions {

        /**
         * Concrete behavioural proof: the same {@link DeletionConflict} flips
         * from "developer can approve" to "developer is blocked" when a stricter
         * thresholds config is loaded from disk.  Without the wiring, the
         * loaded config would have no observable effect.
         */
        @Test
        @DisplayName("stricter project config makes a previously-approvable deletion exceed the role's max severity")
        void strictConfigBlocksConflict(@TempDir Path repoDir) throws Exception {
            try (var ignored = initRepo(repoDir)) {
                // A deletion with 2 lost CONSEQUENTIAL updates → weighted impact 2.
                // - Default thresholds (1/3/10) → impact 2 < 3 → MEDIUM
                // - Strict thresholds (1/2/10)  → impact 2 >= 2 → HIGH
                // Developer.maxSeverity = MEDIUM, so the routing decision flips.
                DeletionConflict conflict = deletionConflict(
                        ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.CONSEQUENTIAL);

                // Step 1: no project config — caller's defaults apply, developer can approve
                MergeManager defaults = new MergeManager(repoDir);
                MergePolicy callerPolicy = MergePolicy.forRole(RoleDefinition.developer());
                MergePolicy effectiveWithDefaults = defaults.effectivePolicyFor(callerPolicy);

                assertEquals(ConflictSeverity.MEDIUM, conflict.getSeverity(effectiveWithDefaults.getSeverityThresholds()));
                assertTrue(effectiveWithDefaults.canApproveDeletion(conflict),
                        "under default thresholds, developer can approve a 2-impact deletion");

                // Step 2: stricter project config — same caller policy now produces a blocking decision
                new SeverityThresholds(1, 2, 10).save(repoDir.resolve(".vitruvius").resolve("config"));
                MergeManager strict = new MergeManager(repoDir);
                MergePolicy effectiveWithStrict = strict.effectivePolicyFor(callerPolicy);

                assertEquals(ConflictSeverity.HIGH, conflict.getSeverity(effectiveWithStrict.getSeverityThresholds()));
                assertFalse(effectiveWithStrict.canApproveDeletion(conflict),
                        "wiring must propagate the loaded thresholds — developer is now blocked");
                assertTrue(effectiveWithStrict.requiresEscalation(conflict));
            }
        }

        private static DeletionConflict deletionConflict(ChangeOrigin... updateOrigins) {
            List<SemanticChangeEntry> updates = new ArrayList<>();
            int i = 0;
            for (ChangeOrigin origin : updateOrigins) {
                updates.add(SemanticChangeEntry.builder()
                        .index(i++)
                        .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                        .emfType("ReplaceSingleValuedEAttribute")
                        .elementUuid("uuid-" + i)
                        .feature("name")
                        .origin(origin)
                        .build());
            }
            return new DeletionConflict("deleted-uuid", "entities::Entity",
                    "feature", "master", updates, true, ChangeOrigin.ORIGINAL);
        }
    }
}
