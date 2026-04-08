package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UserProfile}.
 */
class UserProfileTest {

    @Test
    @DisplayName("Profile creation with all fields")
    void profileCreation() {
        UserProfile profile = new UserProfile("Tural@kit.edu", "DEVELOPER", false,
                "supervisor@kit.edu", "2026-04-06T14:00:00");

        assertEquals("tural@kit.edu", profile.getUserId()); // lowercased
        assertEquals("DEVELOPER", profile.getRoleName());
        assertFalse(profile.isAdmin());
        assertEquals("supervisor@kit.edu", profile.getAssignedBy());
        assertEquals("2026-04-06T14:00:00", profile.getAssignedAt());
    }

    @Test
    @DisplayName("Factory method sets current timestamp")
    void factoryMethodTimestamp() {
        UserProfile profile = UserProfile.create("user@kit.edu", "DEVELOPER", false, "admin@kit.edu");

        assertNotNull(profile.getAssignedAt());
        assertFalse(profile.getAssignedAt().isBlank());
    }

    @Test
    @DisplayName("Null userId throws")
    void nullUserIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new UserProfile(null, "DEVELOPER", false, "admin", "now"));
    }

    @Test
    @DisplayName("Blank userId throws")
    void blankUserIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new UserProfile("  ", "DEVELOPER", false, "admin", "now"));
    }

    @Test
    @DisplayName("Null roleName throws")
    void nullRoleNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new UserProfile("user@kit.edu", null, false, "admin", "now"));
    }

    @Test
    @DisplayName("Role name is uppercased")
    void roleNameUppercased() {
        UserProfile profile = UserProfile.create("user@kit.edu", "developer", false, "admin");
        assertEquals("DEVELOPER", profile.getRoleName());
    }

    @Test
    @DisplayName("setRoleName updates role and audit trail")
    void setRoleNameUpdates() {
        UserProfile profile = UserProfile.create("user@kit.edu", "DEVELOPER", false, "admin1");

        profile.setRoleName("METHODOLOGIST", "admin2");

        assertEquals("METHODOLOGIST", profile.getRoleName());
        assertEquals("admin2", profile.getAssignedBy());
        assertNotNull(profile.getAssignedAt());
    }

    @Test
    @DisplayName("grantAdmin sets admin to true")
    void grantAdmin() {
        UserProfile profile = UserProfile.create("user@kit.edu", "DEVELOPER", false, "admin");

        assertFalse(profile.isAdmin());
        profile.grantAdmin("supervisor@kit.edu");
        assertTrue(profile.isAdmin());
        assertEquals("supervisor@kit.edu", profile.getAssignedBy());
    }

    @Test
    @DisplayName("revokeAdmin sets admin to false")
    void revokeAdmin() {
        UserProfile profile = UserProfile.create("user@kit.edu", "METHODOLOGIST", true, "system");

        assertTrue(profile.isAdmin());
        profile.revokeAdmin("supervisor@kit.edu");
        assertFalse(profile.isAdmin());
    }

    @Test
    @DisplayName("Equality is based on userId only")
    void equalityByUserId() {
        UserProfile a = UserProfile.create("user@kit.edu", "DEVELOPER", false, "admin");
        UserProfile b = UserProfile.create("user@kit.edu", "METHODOLOGIST", true, "other");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Different userIds are not equal")
    void notEqualDifferentId() {
        UserProfile a = UserProfile.create("user1@kit.edu", "DEVELOPER", false, "admin");
        UserProfile b = UserProfile.create("user2@kit.edu", "DEVELOPER", false, "admin");

        assertNotEquals(a, b);
    }
}
