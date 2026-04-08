package tools.vitruv.framework.vsum.branch.storage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoleCommand}.
 */
class RoleCommandTest {

    @TempDir
    Path tempDir;

    private static final String ADMIN_EMAIL = "admin@kit.edu";
    private ByteArrayOutputStream outputBuffer;
    private RoleCommand command;

    @BeforeEach
    void setUp() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        git.getRepository().getConfig().setString("user", null, "email", ADMIN_EMAIL);
        git.getRepository().getConfig().save();
        git.close();

        outputBuffer = new ByteArrayOutputStream();
        RoleManager manager = new RoleManager(tempDir);
        command = new RoleCommand(manager, new PrintStream(outputBuffer));
    }

    private String output() {
        return outputBuffer.toString();
    }

    @Test
    @DisplayName("list-roles shows built-in roles")
    void listRoles() {
        int result = command.execute(new String[]{"list-roles"});
        assertEquals(0, result);
        assertTrue(output().contains("METHODOLOGIST"));
        assertTrue(output().contains("DEVELOPER"));
        assertTrue(output().contains("Total: 2 role(s)"));
    }

    @Test
    @DisplayName("list-users shows bootstrap user")
    void listUsers() {
        int result = command.execute(new String[]{"list-users"});
        assertEquals(0, result);
        assertTrue(output().contains(ADMIN_EMAIL));
        assertTrue(output().contains("METHODOLOGIST"));
        assertTrue(output().contains("Total: 1 user(s)"));
    }

    @Test
    @DisplayName("whoami shows current user")
    void whoami() {
        int result = command.execute(new String[]{"whoami"});
        assertEquals(0, result);
        assertTrue(output().contains(ADMIN_EMAIL));
        assertTrue(output().contains("METHODOLOGIST"));
        assertTrue(output().contains("Admin:       YES"));
    }

    @Test
    @DisplayName("create-role creates a custom role")
    void createRole() {
        int result = command.execute(new String[]{
                "create-role", "SENIOR_DEV", "7", "10", "HIGH", "Senior developer"});
        assertEquals(0, result);
        assertTrue(output().contains("created successfully"));

        // Verify it appears in list
        outputBuffer.reset();
        command.execute(new String[]{"list-roles"});
        assertTrue(output().contains("SENIOR_DEV"));
        assertTrue(output().contains("Total: 3 role(s)"));
    }

    @Test
    @DisplayName("create-role with insufficient args shows usage")
    void createRoleInsufficientArgs() {
        int result = command.execute(new String[]{"create-role", "NAME"});
        assertEquals(1, result);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    @DisplayName("assign creates user profile")
    void assignUser() {
        int result = command.execute(new String[]{"assign", "dev@kit.edu", "DEVELOPER"});
        assertEquals(0, result);
        assertTrue(output().contains("assigned to role"));

        // Verify it appears in list
        outputBuffer.reset();
        command.execute(new String[]{"list-users"});
        assertTrue(output().contains("dev@kit.edu"));
    }

    @Test
    @DisplayName("assign to nonexistent role shows error")
    void assignNonexistentRole() {
        int result = command.execute(new String[]{"assign", "dev@kit.edu", "NONEXISTENT"});
        assertEquals(1, result);
        assertTrue(output().contains("ERROR:"));
    }

    @Test
    @DisplayName("grant-admin and revoke-admin work")
    void grantAndRevokeAdmin() throws IOException {
        command.execute(new String[]{"assign", "dev@kit.edu", "DEVELOPER"});
        outputBuffer.reset();

        int grantResult = command.execute(new String[]{"grant-admin", "dev@kit.edu"});
        assertEquals(0, grantResult);
        assertTrue(output().contains("granted"));

        outputBuffer.reset();
        int revokeResult = command.execute(new String[]{"revoke-admin", "dev@kit.edu"});
        assertEquals(0, revokeResult);
        assertTrue(output().contains("revoked"));
    }

    @Test
    @DisplayName("delete-role works for custom roles")
    void deleteRole() {
        command.execute(new String[]{"create-role", "TEMP", "2", "1", "LOW", "Temporary"});
        outputBuffer.reset();

        int result = command.execute(new String[]{"delete-role", "TEMP"});
        assertEquals(0, result);
        assertTrue(output().contains("deleted"));
    }

    @Test
    @DisplayName("delete-role blocked for built-in roles")
    void deleteBuiltinRole() {
        int result = command.execute(new String[]{"delete-role", "DEVELOPER"});
        assertEquals(1, result);
        assertTrue(output().contains("ERROR:"));
    }

    @Test
    @DisplayName("Unknown command returns error")
    void unknownCommand() {
        int result = command.execute(new String[]{"foobar"});
        assertEquals(1, result);
        assertTrue(output().contains("Unknown command"));
    }

    @Test
    @DisplayName("No args shows usage")
    void noArgs() {
        int result = command.execute(new String[]{});
        assertEquals(1, result);
        assertTrue(output().contains("Vitruvius Role Administration"));
    }

    @Test
    @DisplayName("help shows usage")
    void help() {
        int result = command.execute(new String[]{"help"});
        assertEquals(0, result);
        assertTrue(output().contains("Commands:"));
    }
}
