package tools.vitruv.framework.vsum.branch.storage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.data.UserProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoleManager}.
 */
class RoleManagerTest {

    @TempDir
    Path tempDir;

    private static final String ADMIN_EMAIL = "admin@kit.edu";

    @BeforeEach
    void setUp() throws Exception {
        // Initialize a Git repo with a known user.email
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        git.getRepository().getConfig().setString("user", null, "email", ADMIN_EMAIL);
        git.getRepository().getConfig().save();
        git.close();
    }

    // ── Bootstrap ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Bootstrap creates built-in roles and assigns current user")
    void bootstrapCreatesDefaults() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        // Built-in roles exist
        assertEquals(2, manager.listRoles().size());
        assertTrue(manager.findRole("METHODOLOGIST").isPresent());
        assertTrue(manager.findRole("DEVELOPER").isPresent());

        // Current user is METHODOLOGIST + admin
        assertEquals(1, manager.listUsers().size());
        UserProfile profile = manager.findUser(ADMIN_EMAIL).orElseThrow();
        assertEquals("METHODOLOGIST", profile.getRoleName());
        assertTrue(profile.isAdmin());
    }

    @Test
    @DisplayName("Bootstrap persists to disk")
    void bootstrapPersistsToDisk() throws IOException {
        new RoleManager(tempDir);

        assertTrue(Files.exists(tempDir.resolve(".vitruvius/config/roles.json")));
        assertTrue(Files.exists(tempDir.resolve(".vitruvius/config/users.json")));
    }

    @Test
    @DisplayName("Second instantiation loads from disk instead of re-bootstrapping")
    void loadFromDisk() throws IOException {
        RoleManager first = new RoleManager(tempDir);
        first.createRole(ADMIN_EMAIL, new RoleDefinition(
                "TESTER", 3, 1, ConflictSeverity.LOW, "Test role", false));

        // Second instance should load the persisted state
        RoleManager second = new RoleManager(tempDir);
        assertEquals(3, second.listRoles().size());
        assertTrue(second.findRole("TESTER").isPresent());
    }

    // ── Role CRUD ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin can create a custom role")
    void createCustomRole() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        RoleDefinition customRole = new RoleDefinition(
                "SENIOR_DEV", 7, 10, ConflictSeverity.HIGH, "Senior developer", false);

        manager.createRole(ADMIN_EMAIL, customRole);

        assertEquals(3, manager.listRoles().size());
        RoleDefinition found = manager.findRole("SENIOR_DEV").orElseThrow();
        assertEquals(7, found.getPermissionLevel());
        assertEquals(10, found.getMaxLostUpdates());
    }

    @Test
    @DisplayName("Cannot create duplicate role")
    void duplicateRoleThrows() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
                manager.createRole(ADMIN_EMAIL, RoleDefinition.developer()));
    }

    @Test
    @DisplayName("Non-admin cannot create a role")
    void nonAdminCannotCreateRole() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "junior@kit.edu", "DEVELOPER");

        assertThrows(SecurityException.class, () ->
                manager.createRole("junior@kit.edu", new RoleDefinition(
                        "HACK", 99, -1, ConflictSeverity.CRITICAL, "Unauthorized", false)));
    }

    @Test
    @DisplayName("Admin can delete a custom role")
    void deleteCustomRole() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        RoleDefinition custom = new RoleDefinition(
                "TEMP", 2, 1, ConflictSeverity.LOW, "Temporary", false);
        manager.createRole(ADMIN_EMAIL, custom);

        assertEquals(3, manager.listRoles().size());
        manager.deleteRole(ADMIN_EMAIL, "TEMP");
        assertEquals(2, manager.listRoles().size());
    }

    @Test
    @DisplayName("Cannot delete built-in role")
    void cannotDeleteBuiltInRole() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
                manager.deleteRole(ADMIN_EMAIL, "METHODOLOGIST"));
        assertThrows(IllegalArgumentException.class, () ->
                manager.deleteRole(ADMIN_EMAIL, "DEVELOPER"));
    }

    @Test
    @DisplayName("Cannot delete role with assigned users")
    void cannotDeleteRoleWithUsers() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        RoleDefinition custom = new RoleDefinition(
                "QA_ENGINEER", 4, 5, ConflictSeverity.MEDIUM, "QA", false);
        manager.createRole(ADMIN_EMAIL, custom);
        manager.assignRole(ADMIN_EMAIL, "qa@kit.edu", "QA_ENGINEER");

        assertThrows(IllegalStateException.class, () ->
                manager.deleteRole(ADMIN_EMAIL, "QA_ENGINEER"));
    }

    // ── User assignment ─────────────────────────────────────────────────

    @Test
    @DisplayName("Admin can assign a user to a role")
    void assignUser() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        manager.assignRole(ADMIN_EMAIL, "new@kit.edu", "DEVELOPER");

        UserProfile profile = manager.findUser("new@kit.edu").orElseThrow();
        assertEquals("DEVELOPER", profile.getRoleName());
        assertFalse(profile.isAdmin());
    }

    @Test
    @DisplayName("Assigning METHODOLOGIST role auto-grants admin")
    void assignMethodologistGrantsAdmin() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        manager.assignRole(ADMIN_EMAIL, "prof@kit.edu", "METHODOLOGIST");

        UserProfile profile = manager.findUser("prof@kit.edu").orElseThrow();
        assertTrue(profile.isAdmin());
    }

    @Test
    @DisplayName("Reassigning a user updates their role")
    void reassignUser() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "user@kit.edu", "DEVELOPER");

        manager.assignRole(ADMIN_EMAIL, "user@kit.edu", "METHODOLOGIST");

        assertEquals("METHODOLOGIST", manager.findUser("user@kit.edu").orElseThrow().getRoleName());
    }

    @Test
    @DisplayName("Cannot assign to a nonexistent role")
    void assignNonexistentRoleThrows() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
                manager.assignRole(ADMIN_EMAIL, "user@kit.edu", "NONEXISTENT"));
    }

    @Test
    @DisplayName("Non-admin cannot assign roles")
    void nonAdminCannotAssign() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "junior@kit.edu", "DEVELOPER");

        assertThrows(SecurityException.class, () ->
                manager.assignRole("junior@kit.edu", "other@kit.edu", "DEVELOPER"));
    }

    // ── Admin capability ────────────────────────────────────────────────

    @Test
    @DisplayName("Admin can grant admin to another user")
    void grantAdmin() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "user@kit.edu", "DEVELOPER");

        manager.grantAdmin(ADMIN_EMAIL, "user@kit.edu");

        assertTrue(manager.findUser("user@kit.edu").orElseThrow().isAdmin());
    }

    @Test
    @DisplayName("Admin can revoke admin from another user")
    void revokeAdmin() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "user@kit.edu", "DEVELOPER");
        manager.grantAdmin(ADMIN_EMAIL, "user@kit.edu");

        manager.revokeAdmin(ADMIN_EMAIL, "user@kit.edu");

        assertFalse(manager.findUser("user@kit.edu").orElseThrow().isAdmin());
    }

    @Test
    @DisplayName("Cannot revoke admin from last admin")
    void cannotRevokeLastAdmin() throws IOException {
        RoleManager manager = new RoleManager(tempDir);

        // admin@kit.edu is the only admin
        assertThrows(IllegalStateException.class, () ->
                manager.revokeAdmin(ADMIN_EMAIL, ADMIN_EMAIL));
    }

    @Test
    @DisplayName("Can revoke admin if another admin exists")
    void canRevokeIfAnotherAdminExists() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "other@kit.edu", "DEVELOPER");
        manager.grantAdmin(ADMIN_EMAIL, "other@kit.edu");

        // Now there are 2 admins, so revoking one is fine
        assertDoesNotThrow(() -> manager.revokeAdmin(ADMIN_EMAIL, "other@kit.edu"));
    }

    @Test
    @DisplayName("Non-admin cannot grant admin")
    void nonAdminCannotGrantAdmin() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "user@kit.edu", "DEVELOPER");

        assertThrows(SecurityException.class, () ->
                manager.grantAdmin("user@kit.edu", "user@kit.edu"));
    }

    // ── Runtime queries ─────────────────────────────────────────────────

    @Test
    @DisplayName("getCurrentUserId resolves from Git config")
    void getCurrentUserId() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        assertEquals(ADMIN_EMAIL, manager.getCurrentUserId());
    }

    @Test
    @DisplayName("getCurrentUserRole resolves to METHODOLOGIST for bootstrap user")
    void getCurrentUserRole() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        RoleDefinition role = manager.getCurrentUserRole();
        assertEquals("METHODOLOGIST", role.getName());
    }

    @Test
    @DisplayName("Unknown user defaults to DEVELOPER")
    void unknownUserDefaultsToDeveloper() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        RoleDefinition role = manager.getRoleForUser("stranger@example.com");
        assertEquals("DEVELOPER", role.getName());
    }

    @Test
    @DisplayName("isAdmin is false for unknown users")
    void isAdminFalseForUnknown() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        assertFalse(manager.isAdmin("stranger@example.com"));
    }

    @Test
    @DisplayName("findRole is case-insensitive")
    void findRoleCaseInsensitive() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        assertTrue(manager.findRole("methodologist").isPresent());
        assertTrue(manager.findRole("METHODOLOGIST").isPresent());
        assertTrue(manager.findRole("Methodologist").isPresent());
    }

    @Test
    @DisplayName("findUser is case-insensitive")
    void findUserCaseInsensitive() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        assertTrue(manager.findUser("ADMIN@kit.edu").isPresent());
        assertTrue(manager.findUser("admin@kit.edu").isPresent());
    }

    // ── SecurityException messages ──────────────────────────────────────

    @Test
    @DisplayName("SecurityException contains actionable message")
    void securityExceptionMessage() throws IOException {
        RoleManager manager = new RoleManager(tempDir);
        manager.assignRole(ADMIN_EMAIL, "junior@kit.edu", "DEVELOPER");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                manager.createRole("junior@kit.edu", new RoleDefinition(
                        "HACK", 99, -1, ConflictSeverity.CRITICAL, "desc", false)));

        assertTrue(ex.getMessage().contains("junior@kit.edu"));
        assertTrue(ex.getMessage().contains("not an admin"));
        assertTrue(ex.getMessage().contains("create a role"));
    }
}
