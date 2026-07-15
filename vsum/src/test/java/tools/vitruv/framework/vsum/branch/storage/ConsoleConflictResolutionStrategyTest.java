package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;
import tools.vitruv.framework.vsum.branch.data.ManualResolution;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the interactive update-conflict resolution path.
 *
 * <p>The surefire test runner has no attached console, so
 * {@link ConsoleConflictResolutionStrategy} runs its headless branch and defers
 * every conflict. This locks in the conservative "never guess without a human"
 * contract and exercises {@link ManualResolution} / {@link AuditLogEntry}.
 */
class ConsoleConflictResolutionStrategyTest {

    private static UpdateConflict conflict() {
        SemanticChangeEntry src = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED).emfType("ATTRIBUTE_CHANGED")
                .elementUuid("uuid-1").eClass("entities::Entity").feature("name").origin(ChangeOrigin.ORIGINAL).build();
        SemanticChangeEntry tgt = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED).emfType("ATTRIBUTE_CHANGED")
                .elementUuid("uuid-1").eClass("entities::Entity").feature("name").origin(ChangeOrigin.ORIGINAL).build();
        return new UpdateConflict("uuid-1", "entities::Entity", "name", "feature", "main", src, tgt);
    }

    @Test
    @DisplayName("headless mode defers every conflict")
    void headlessDefers() {
        ManualResolution resolution = new ConsoleConflictResolutionStrategy().resolve(conflict());
        assertFalse(resolution.isResolved());
        assertEquals(ManualResolution.Decision.DEFERRED, resolution.getDecision());
        assertNull(resolution.getChosenEntry());
    }

    @Test
    @DisplayName("acceptSource / acceptTarget carry the chosen side and branch")
    void manualResolutionFactories() {
        UpdateConflict c = conflict();

        ManualResolution source = ManualResolution.acceptSource(c, "prefer incoming");
        assertTrue(source.isResolved());
        assertSame(c.getSourceEntry(), source.getChosenEntry());
        assertEquals("feature", source.getChosenBranch());

        ManualResolution target = ManualResolution.acceptTarget(c, null);
        assertSame(c.getTargetEntry(), target.getChosenEntry());
        assertEquals("main", target.getChosenBranch());
    }

    @Test
    @DisplayName("audit entry can be built from a resolved manual decision but not a deferred one")
    void auditEntryFromManual() {
        UpdateConflict c = conflict();
        AuditLogEntry entry = AuditLogEntry.forUpdateManual(
                ManualResolution.acceptTarget(c, "kept main's value"), "feature", "main");
        assertEquals("UPDATE_MANUAL", entry.getConflictType());
        assertEquals("kept main's value", entry.getRationale());

        ManualResolution deferred = ManualResolution.deferred(c, "skipped");
        assertThrows(IllegalArgumentException.class,
                () -> AuditLogEntry.forUpdateManual(deferred, "feature", "main"));
    }
}
