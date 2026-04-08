package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.data.UserProfile;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Command-line interface for administering roles and user assignments
 * in the Vitruvius merge conflict resolution system.
 *
 * <p>This class wraps the {@link RoleManager} API with a human-friendly
 * CLI that administrators invoke to:
 * <ul>
 *   <li>Create and delete custom roles</li>
 *   <li>Assign users to roles</li>
 *   <li>Grant or revoke admin capability</li>
 *   <li>List all roles and users</li>
 * </ul>
 *
 * <p>All operations require admin capability. The current user is
 * automatically identified from {@code git config user.email}.
 *
 * <p>Usage examples:
 * <pre>
 *   RoleCommand cmd = new RoleCommand(repoRoot);
 *   cmd.execute(new String[]{"list-roles"});
 *   cmd.execute(new String[]{"create-role", "SENIOR_DEV", "7", "10", "HIGH", "Senior developer"});
 *   cmd.execute(new String[]{"assign", "alice@kit.edu", "SENIOR_DEV"});
 *   cmd.execute(new String[]{"grant-admin", "alice@kit.edu"});
 * </pre>
 *
 * @see RoleManager
 */
public class RoleCommand {

    private static final Logger LOGGER = LogManager.getLogger(RoleCommand.class);

    private final RoleManager roleManager;
    private final PrintStream out;

    /**
     * Creates a RoleCommand for the given repository root.
     *
     * @param repoRoot root directory of the Git repository.
     * @throws IOException if the role configuration cannot be loaded.
     */
    public RoleCommand(Path repoRoot) throws IOException {
        this(new RoleManager(repoRoot), System.out);
    }

    /**
     * Creates a RoleCommand with explicit dependencies (for testing).
     */
    public RoleCommand(RoleManager roleManager, PrintStream out) {
        this.roleManager = roleManager;
        this.out = out;
    }

    /**
     * Executes a role administration command.
     *
     * @param args command arguments, where args[0] is the subcommand.
     * @return 0 on success, 1 on error.
     */
    public int execute(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            return 1;
        }

        String subcommand = args[0].toLowerCase();
        try {
            switch (subcommand) {
                case "list-roles":
                    return listRoles();
                case "list-users":
                    return listUsers();
                case "create-role":
                    return createRole(args);
                case "delete-role":
                    return deleteRole(args);
                case "assign":
                    return assignRole(args);
                case "grant-admin":
                    return grantAdmin(args);
                case "revoke-admin":
                    return revokeAdmin(args);
                case "whoami":
                    return whoami();
                case "help":
                    printUsage();
                    return 0;
                default:
                    out.printf("Unknown command: '%s'. Use 'help' for available commands.%n", subcommand);
                    return 1;
            }
        } catch (SecurityException e) {
            out.printf("PERMISSION DENIED: %s%n", e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.printf("ERROR: %s%n", e.getMessage());
            return 1;
        } catch (IOException e) {
            out.printf("I/O ERROR: %s%n", e.getMessage());
            return 1;
        }
    }

    private int listRoles() {
        List<RoleDefinition> roles = roleManager.listRoles();
        out.printf("%n%-20s %-6s %-12s %-10s %-8s %s%n",
                "ROLE", "LEVEL", "MAX_UPDATES", "MAX_SEV", "BUILTIN", "DESCRIPTION");
        out.println("-".repeat(85));
        for (RoleDefinition role : roles) {
            out.printf("%-20s %-6d %-12s %-10s %-8s %s%n",
                    role.getName(),
                    role.getPermissionLevel(),
                    role.getMaxLostUpdates() == -1 ? "unlimited" : role.getMaxLostUpdates(),
                    role.getMaxSeverity(),
                    role.isBuiltIn() ? "yes" : "no",
                    role.getDescription());
        }
        out.printf("%nTotal: %d role(s)%n", roles.size());
        return 0;
    }

    private int listUsers() {
        List<UserProfile> users = roleManager.listUsers();
        out.printf("%n%-30s %-20s %-8s %-25s %s%n",
                "USER", "ROLE", "ADMIN", "ASSIGNED_BY", "ASSIGNED_AT");
        out.println("-".repeat(110));
        for (UserProfile user : users) {
            out.printf("%-30s %-20s %-8s %-25s %s%n",
                    user.getUserId(),
                    user.getRoleName(),
                    user.isAdmin() ? "yes" : "no",
                    user.getAssignedBy() != null ? user.getAssignedBy() : "-",
                    user.getAssignedAt() != null ? user.getAssignedAt() : "-");
        }
        out.printf("%nTotal: %d user(s)%n", users.size());
        return 0;
    }

    /**
     * create-role NAME LEVEL MAX_UPDATES MAX_SEVERITY DESCRIPTION
     * Example: create-role SENIOR_DEV 7 10 HIGH "Senior developer"
     */
    private int createRole(String[] args) throws IOException {
        if (args.length < 6) {
            out.println("Usage: create-role <name> <level> <max_updates> <max_severity> <description>");
            out.println("  max_updates: -1 for unlimited");
            out.println("  max_severity: LOW, MEDIUM, HIGH, CRITICAL");
            out.println("Example: create-role SENIOR_DEV 7 10 HIGH \"Senior developer\"");
            return 1;
        }

        String name = args[1];
        int level = Integer.parseInt(args[2]);
        int maxUpdates = Integer.parseInt(args[3]);
        ConflictSeverity maxSeverity = ConflictSeverity.valueOf(args[4].toUpperCase());
        String description = args[5];

        RoleDefinition role = new RoleDefinition(name, level, maxUpdates, maxSeverity, description, false);
        String callerId = roleManager.getCurrentUserId();
        roleManager.createRole(callerId, role);

        out.printf("Role '%s' created successfully (level=%d, maxUpdates=%s, maxSeverity=%s).%n",
                role.getName(), level,
                maxUpdates == -1 ? "unlimited" : maxUpdates,
                maxSeverity);
        return 0;
    }

    /**
     * delete-role NAME
     */
    private int deleteRole(String[] args) throws IOException {
        if (args.length < 2) {
            out.println("Usage: delete-role <name>");
            return 1;
        }
        String callerId = roleManager.getCurrentUserId();
        roleManager.deleteRole(callerId, args[1]);
        out.printf("Role '%s' deleted successfully.%n", args[1]);
        return 0;
    }

    /**
     * assign USER ROLE
     * Example: assign alice@kit.edu DEVELOPER
     */
    private int assignRole(String[] args) throws IOException {
        if (args.length < 3) {
            out.println("Usage: assign <user_email> <role_name>");
            out.println("Example: assign alice@kit.edu DEVELOPER");
            return 1;
        }
        String callerId = roleManager.getCurrentUserId();
        roleManager.assignRole(callerId, args[1], args[2]);
        out.printf("User '%s' assigned to role '%s'.%n", args[1], args[2].toUpperCase());
        return 0;
    }

    /**
     * grant-admin USER
     */
    private int grantAdmin(String[] args) throws IOException {
        if (args.length < 2) {
            out.println("Usage: grant-admin <user_email>");
            return 1;
        }
        String callerId = roleManager.getCurrentUserId();
        roleManager.grantAdmin(callerId, args[1]);
        out.printf("Admin capability granted to '%s'.%n", args[1]);
        return 0;
    }

    /**
     * revoke-admin USER
     */
    private int revokeAdmin(String[] args) throws IOException {
        if (args.length < 2) {
            out.println("Usage: revoke-admin <user_email>");
            return 1;
        }
        String callerId = roleManager.getCurrentUserId();
        roleManager.revokeAdmin(callerId, args[1]);
        out.printf("Admin capability revoked from '%s'.%n", args[1]);
        return 0;
    }

    /**
     * whoami - shows the current user's identity, role, and admin status.
     */
    private int whoami() {
        String userId = roleManager.getCurrentUserId();
        RoleDefinition role = roleManager.getCurrentUserRole();
        boolean admin = roleManager.isCurrentUserAdmin();

        out.printf("%nCurrent User:%n");
        out.printf("  Identity:    %s (from git config user.email)%n", userId);
        out.printf("  Role:        %s (level %d)%n", role.getName(), role.getPermissionLevel());
        out.printf("  Max updates: %s%n", role.getMaxLostUpdates() == -1 ? "unlimited" : role.getMaxLostUpdates());
        out.printf("  Max severity: %s%n", role.getMaxSeverity());
        out.printf("  Admin:       %s%n", admin ? "YES" : "no");
        out.printf("  Description: %s%n%n", role.getDescription());
        return 0;
    }

    private void printUsage() {
        out.println();
        out.println("Vitruvius Role Administration");
        out.println("=============================");
        out.println();
        out.println("Commands:");
        out.println("  list-roles                              List all defined roles");
        out.println("  list-users                              List all user assignments");
        out.println("  create-role NAME LVL MAX SEV DESC       Create a custom role");
        out.println("  delete-role NAME                        Delete a custom role");
        out.println("  assign USER ROLE                        Assign a user to a role");
        out.println("  grant-admin USER                        Grant admin capability");
        out.println("  revoke-admin USER                       Revoke admin capability");
        out.println("  whoami                                  Show current user info");
        out.println("  help                                    Show this help");
        out.println();
        out.println("Examples:");
        out.println("  create-role SENIOR_DEV 7 10 HIGH \"Senior developer\"");
        out.println("  assign alice@kit.edu SENIOR_DEV");
        out.println("  grant-admin bob@kit.edu");
        out.println();
    }
}
