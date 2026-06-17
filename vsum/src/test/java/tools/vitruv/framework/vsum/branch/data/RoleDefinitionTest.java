package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoleDefinition}.
 */
class RoleDefinitionTest {

    @Test
    @DisplayName("Methodologist factory produces correct defaults")
    void methodologistDefaults() {
        RoleDefinition role = RoleDefinition.methodologist();

        assertEquals("METHODOLOGIST", role.getName());
        assertEquals(10, role.getPermissionLevel());
        assertEquals(-1, role.getMaxLostUpdates());
        assertEquals(ConflictSeverity.CRITICAL, role.getMaxSeverity());
        assertTrue(role.isBuiltIn());
        assertTrue(role.hasUnlimitedDeletionApproval());
    }

    @Test
    @DisplayName("Developer factory produces correct defaults")
    void developerDefaults() {
        RoleDefinition role = RoleDefinition.developer();

        assertEquals("DEVELOPER", role.getName());
        assertEquals(5, role.getPermissionLevel());
        assertEquals(3, role.getMaxLostUpdates());
        assertEquals(ConflictSeverity.MEDIUM, role.getMaxSeverity());
        assertTrue(role.isBuiltIn());
        assertFalse(role.hasUnlimitedDeletionApproval());
    }

    @Test
    @DisplayName("Custom role creation with valid parameters")
    void customRoleCreation() {
        RoleDefinition role = new RoleDefinition(
                "senior_developer", 7, 10, ConflictSeverity.HIGH,
                "Senior developer with elevated merge permissions", false);

        assertEquals("SENIOR_DEVELOPER", role.getName()); // uppercased
        assertEquals(7, role.getPermissionLevel());
        assertEquals(10, role.getMaxLostUpdates());
        assertEquals(ConflictSeverity.HIGH, role.getMaxSeverity());
        assertFalse(role.isBuiltIn());
    }

    @Test
    @DisplayName("Blank name throws IllegalArgumentException")
    void blankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RoleDefinition("", 5, 3, ConflictSeverity.MEDIUM, "desc", false));
        assertThrows(IllegalArgumentException.class, () ->
                new RoleDefinition("   ", 5, 3, ConflictSeverity.MEDIUM, "desc", false));
    }

    @Test
    @DisplayName("Null name throws IllegalArgumentException")
    void nullNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RoleDefinition(null, 5, 3, ConflictSeverity.MEDIUM, "desc", false));
    }

    @Test
    @DisplayName("Zero or negative permission level throws")
    void invalidPermissionLevelThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RoleDefinition("TEST", 0, 3, ConflictSeverity.MEDIUM, "desc", false));
        assertThrows(IllegalArgumentException.class, () ->
                new RoleDefinition("TEST", -1, 3, ConflictSeverity.MEDIUM, "desc", false));
    }

    @Test
    @DisplayName("maxLostUpdates below -1 throws")
    void invalidMaxLostUpdatesThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new RoleDefinition("TEST", 5, -2, ConflictSeverity.MEDIUM, "desc", false));
    }

    @Test
    @DisplayName("Null maxSeverity throws NullPointerException")
    void nullMaxSeverityThrows() {
        assertThrows(NullPointerException.class, () ->
                new RoleDefinition("TEST", 5, 3, null, "desc", false));
    }

    @Test
    @DisplayName("canApproveUpdatesLost respects limit")
    void canApproveUpdatesLost() {
        RoleDefinition dev = RoleDefinition.developer(); // max 3

        assertTrue(dev.canApproveUpdatesLost(0));
        assertTrue(dev.canApproveUpdatesLost(1));
        assertTrue(dev.canApproveUpdatesLost(2));
        assertTrue(dev.canApproveUpdatesLost(3));   // inclusive upper bound
        assertFalse(dev.canApproveUpdatesLost(4));
        assertFalse(dev.canApproveUpdatesLost(10));
    }

    @Test
    @DisplayName("Unlimited role can approve any number of lost updates")
    void unlimitedCanApproveAnything() {
        RoleDefinition methodologist = RoleDefinition.methodologist(); // unlimited

        assertTrue(methodologist.canApproveUpdatesLost(0));
        assertTrue(methodologist.canApproveUpdatesLost(100));
        assertTrue(methodologist.canApproveUpdatesLost(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("canResolveSeverity respects maxSeverity")
    void canResolveSeverity() {
        RoleDefinition dev = RoleDefinition.developer(); // max MEDIUM

        assertTrue(dev.canResolveSeverity(ConflictSeverity.LOW));
        assertTrue(dev.canResolveSeverity(ConflictSeverity.MEDIUM));
        assertFalse(dev.canResolveSeverity(ConflictSeverity.HIGH));
        assertFalse(dev.canResolveSeverity(ConflictSeverity.CRITICAL));
    }

    @Test
    @DisplayName("Methodologist can resolve all severities")
    void methodologistCanResolveAll() {
        RoleDefinition meth = RoleDefinition.methodologist(); // max CRITICAL

        for (ConflictSeverity severity : ConflictSeverity.values()) {
            assertTrue(meth.canResolveSeverity(severity),
                    "Methodologist should resolve " + severity);
        }
    }

    @Test
    @DisplayName("Equality is based on name only")
    void equalityByName() {
        RoleDefinition a = new RoleDefinition("TEST", 5, 3, ConflictSeverity.LOW, "desc A", false);
        RoleDefinition b = new RoleDefinition("TEST", 10, -1, ConflictSeverity.CRITICAL, "desc B", true);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Different names are not equal")
    void notEqualDifferentName() {
        RoleDefinition a = RoleDefinition.methodologist();
        RoleDefinition b = RoleDefinition.developer();

        assertNotEquals(a, b);
    }
}
