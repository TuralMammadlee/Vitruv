package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.RoleDefinition;
import tools.vitruv.framework.vsum.branch.data.UserProfile;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Central service for managing roles and user assignments in the Vitruvius
 * merge conflict resolution system.
 *
 * <p>All data is persisted in {@code .vitruvius/config/roles.json} and
 * {@code .vitruvius/config/users.json}. These files are managed exclusively
 * through this service — they should never be edited by hand.
 *
 * <p><b>Bootstrap behavior:</b> On first use (no config files exist),
 * the manager creates the two built-in roles ({@code METHODOLOGIST} and
 * {@code DEVELOPER}) and assigns the current Git user as a METHODOLOGIST
 * with admin capability.
 *
 * <p><b>Identity resolution:</b> The current user is identified by their
 * Git config email ({@code user.email}). Unknown users (no profile) default
 * to the DEVELOPER role with no admin capability.
 *
 * <p><b>Admin enforcement:</b> Operations that modify roles or user
 * assignments require admin capability. Non-admin callers receive a
 * meaningful {@link SecurityException}.
 *
 * @see RoleDefinition
 * @see UserProfile
 */
public class RoleManager {

    private static final Logger LOGGER = LogManager.getLogger(RoleManager.class);
    private static final String CONFIG_DIR = ".vitruvius/config";
    private static final String ROLES_FILE = "roles.json";
    private static final String USERS_FILE = "users.json";

    private final Path repoRoot;
    private final Gson gson;

    private List<RoleDefinition> roles;
    private List<UserProfile> users;

    /**
     * Creates a RoleManager for the given repository root.
     * Loads existing configuration or bootstraps defaults if none exists.
     *
     * @param repoRoot root directory of the Git repository, must not be null.
     * @throws IOException if configuration files cannot be read or written.
     */
    public RoleManager(Path repoRoot) throws IOException {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.roles = new ArrayList<>();
        this.users = new ArrayList<>();
        bootstrap();
    }

    // ── Role CRUD (admin-only) ──────────────────────────────────────────

    /**
     * Creates a new custom role. Built-in roles cannot be recreated.
     *
     * @param callerUserId the userId of the person performing this action.
     * @param role         the role definition to create.
     * @throws SecurityException        if the caller is not an admin.
     * @throws IllegalArgumentException if a role with the same name already exists.
     * @throws IOException              if persistence fails.
     */
    public void createRole(String callerUserId, RoleDefinition role) throws IOException {
        requireAdmin(callerUserId, "create a role");
        Objects.requireNonNull(role, "role must not be null");

        if (findRole(role.getName()).isPresent()) {
            throw new IllegalArgumentException("Role already exists: " + role.getName());
        }

        roles.add(role);
        persistRoles();
        LOGGER.info("Role '{}' created by '{}'", role.getName(), callerUserId);
    }

    /**
     * Deletes a custom role. Built-in roles cannot be deleted.
     *
     * @param callerUserId the userId of the person performing this action.
     * @param roleName     the name of the role to delete.
     * @throws SecurityException        if the caller is not an admin.
     * @throws IllegalArgumentException if the role does not exist or is built-in.
     * @throws IllegalStateException    if users are still assigned to this role.
     * @throws IOException              if persistence fails.
     */
    public void deleteRole(String callerUserId, String roleName) throws IOException {
        requireAdmin(callerUserId, "delete a role");
        Objects.requireNonNull(roleName, "roleName must not be null");

        RoleDefinition role = findRole(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role does not exist: " + roleName));

        if (role.isBuiltIn()) {
            throw new IllegalArgumentException("Cannot delete built-in role: " + roleName);
        }

        long assignedCount = users.stream()
                .filter(u -> u.getRoleName().equalsIgnoreCase(roleName))
                .count();
        if (assignedCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete role '" + roleName + "': " + assignedCount + " user(s) still assigned. " +
                    "Reassign them first.");
        }

        roles.removeIf(r -> r.getName().equalsIgnoreCase(roleName));
        persistRoles();
        LOGGER.info("Role '{}' deleted by '{}'", roleName, callerUserId);
    }

    /**
     * Returns all defined roles (built-in and custom).
     */
    public List<RoleDefinition> listRoles() {
        return Collections.unmodifiableList(roles);
    }

    /**
     * Finds a role by name (case-insensitive).
     */
    public Optional<RoleDefinition> findRole(String roleName) {
        if (roleName == null) return Optional.empty();
        return roles.stream()
                .filter(r -> r.getName().equalsIgnoreCase(roleName))
                .findFirst();
    }

    // ── User assignment (admin-only) ────────────────────────────────────

    /**
     * Assigns a user to a role. Creates a new profile if the user doesn't
     * exist, or updates their role if they do.
     *
     * @param callerUserId the userId of the admin performing this action.
     * @param userId       the Git email of the user to assign.
     * @param roleName     the name of the role to assign.
     * @throws SecurityException        if the caller is not an admin.
     * @throws IllegalArgumentException if the role does not exist.
     * @throws IOException              if persistence fails.
     */
    public void assignRole(String callerUserId, String userId, String roleName) throws IOException {
        requireAdmin(callerUserId, "assign a role");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(roleName, "roleName must not be null");

        if (findRole(roleName).isEmpty()) {
            throw new IllegalArgumentException("Role does not exist: " + roleName);
        }

        Optional<UserProfile> existing = findUser(userId);
        if (existing.isPresent()) {
            existing.get().setRoleName(roleName, callerUserId);
            LOGGER.info("User '{}' reassigned to role '{}' by '{}'", userId, roleName, callerUserId);
        } else {
            boolean isMethodologist = roleName.equalsIgnoreCase("METHODOLOGIST");
            UserProfile profile = UserProfile.create(userId, roleName, isMethodologist, callerUserId);
            users.add(profile);
            LOGGER.info("User '{}' assigned to role '{}' by '{}'", userId, roleName, callerUserId);
        }
        persistUsers();
    }

    /**
     * Grants admin capability to a user.
     *
     * @param callerUserId the userId of the admin performing this action.
     * @param userId       the Git email of the user to grant admin to.
     * @throws SecurityException if the caller is not an admin.
     * @throws IllegalArgumentException if the user does not exist.
     * @throws IOException if persistence fails.
     */
    public void grantAdmin(String callerUserId, String userId) throws IOException {
        requireAdmin(callerUserId, "grant admin capability");
        UserProfile profile = findUser(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User does not exist: " + userId + ". Assign a role first."));

        profile.grantAdmin(callerUserId);
        persistUsers();
        LOGGER.info("Admin capability granted to '{}' by '{}'", userId, callerUserId);
    }

    /**
     * Revokes admin capability from a user. Cannot revoke from the last admin.
     *
     * @param callerUserId the userId of the admin performing this action.
     * @param userId       the Git email of the user to revoke admin from.
     * @throws SecurityException    if the caller is not an admin.
     * @throws IllegalStateException if this is the last admin in the system.
     * @throws IOException           if persistence fails.
     */
    public void revokeAdmin(String callerUserId, String userId) throws IOException {
        requireAdmin(callerUserId, "revoke admin capability");
        UserProfile profile = findUser(userId)
                .orElseThrow(() -> new IllegalArgumentException("User does not exist: " + userId));

        long adminCount = users.stream().filter(UserProfile::isAdmin).count();
        if (adminCount <= 1 && profile.isAdmin()) {
            throw new IllegalStateException(
                    "Cannot revoke admin from '" + userId + "': they are the last admin. " +
                    "Grant admin to another user first.");
        }

        profile.revokeAdmin(callerUserId);
        persistUsers();
        LOGGER.info("Admin capability revoked from '{}' by '{}'", userId, callerUserId);
    }

    /**
     * Returns all user profiles.
     */
    public List<UserProfile> listUsers() {
        return Collections.unmodifiableList(users);
    }

    /**
     * Returns normalized user IDs assigned to the given role (case-insensitive).
     */
    public List<String> findUserIdsByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return List.of();
        }
        String normalized = roleName.trim().toUpperCase();
        return users.stream()
                .filter(u -> normalized.equals(u.getRoleName()))
                .map(UserProfile::getUserId)
                .toList();
    }

    /**
     * Returns fallback assignee IDs when blame/semantic owner detection finds no one.
     *
     * <p>Prefers {@code METHODOLOGIST} users; if none exist, returns the first admin.
     */
    public List<String> findFallbackOwnerIds() {
        List<String> methodologists = findUserIdsByRole("METHODOLOGIST");
        if (!methodologists.isEmpty()) {
            return methodologists;
        }
        return users.stream()
                .filter(UserProfile::isAdmin)
                .map(UserProfile::getUserId)
                .limit(1)
                .toList();
    }

    /**
     * Finds a user profile by Git email (case-insensitive).
     */
    public Optional<UserProfile> findUser(String userId) {
        if (userId == null) return Optional.empty();
        return users.stream()
                .filter(u -> u.getUserId().equalsIgnoreCase(userId))
                .findFirst();
    }

    // ── Runtime queries (used by MergePolicy) ───────────────────────────

    /**
     * Resolves the current user's Git email from the repository config.
     * Falls back to {@code "unknown@localhost"} if Git config is not set.
     *
     * @return the current user's email, never null.
     */
    public String getCurrentUserId() {
        try (Git git = Git.open(repoRoot.toFile())) {
            String email = git.getRepository().getConfig()
                    .getString("user", null, "email");
            return email != null && !email.isBlank() ? email.toLowerCase() : "unknown@localhost";
        } catch (Exception e) {
            LOGGER.warn("Could not resolve Git user.email: {}", e.getMessage());
            return "unknown@localhost";
        }
    }

    /**
     * Returns the {@link RoleDefinition} for the current Git user.
     * Unknown users default to DEVELOPER.
     */
    public RoleDefinition getCurrentUserRole() {
        return getRoleForUser(getCurrentUserId());
    }

    /**
     * Returns the {@link RoleDefinition} for the given user.
     * Unknown users default to DEVELOPER.
     */
    public RoleDefinition getRoleForUser(String userId) {
        Optional<UserProfile> profile = findUser(userId);
        if (profile.isEmpty()) {
            LOGGER.debug("No profile for user '{}', defaulting to DEVELOPER", userId);
            return findRole("DEVELOPER").orElse(RoleDefinition.developer());
        }
        return findRole(profile.get().getRoleName())
                .orElse(RoleDefinition.developer());
    }

    /**
     * Returns whether the current Git user has admin capability.
     */
    public boolean isCurrentUserAdmin() {
        return isAdmin(getCurrentUserId());
    }

    /**
     * Returns whether the given user has admin capability.
     */
    public boolean isAdmin(String userId) {
        return findUser(userId).map(UserProfile::isAdmin).orElse(false);
    }

    // ── Bootstrap & Persistence ─────────────────────────────────────────

    /**
     * Initializes the role system. If config files exist, loads them.
     * If not, creates built-in defaults and assigns the current user.
     */
    private void bootstrap() throws IOException {
        Path configDir = repoRoot.resolve(CONFIG_DIR);
        Path rolesPath = configDir.resolve(ROLES_FILE);
        Path usersPath = configDir.resolve(USERS_FILE);

        if (Files.exists(rolesPath) && Files.exists(usersPath)) {
            loadRoles(rolesPath);
            loadUsers(usersPath);
            LOGGER.info("Role configuration loaded: {} role(s), {} user(s)", roles.size(), users.size());
        } else {
            Files.createDirectories(configDir);

            // Create built-in roles
            roles.add(RoleDefinition.methodologist());
            roles.add(RoleDefinition.developer());

            // Assign the current Git user as METHODOLOGIST + admin
            String currentUser = getCurrentUserId();
            users.add(UserProfile.create(currentUser, "METHODOLOGIST", true, "system"));

            persistRoles();
            persistUsers();
            LOGGER.info("Role system bootstrapped. User '{}' assigned as METHODOLOGIST + admin", currentUser);
        }
    }

    private void loadRoles(Path path) throws IOException {
        String json = Files.readString(path);
        Type listType = new TypeToken<List<RoleDefinition>>() {}.getType();
        List<RoleDefinition> loaded = gson.fromJson(json, listType);
        if (loaded != null) {
            this.roles = new ArrayList<>(loaded);
        }
    }

    private void loadUsers(Path path) throws IOException {
        String json = Files.readString(path);
        Type listType = new TypeToken<List<UserProfile>>() {}.getType();
        List<UserProfile> loaded = gson.fromJson(json, listType);
        if (loaded != null) {
            this.users = new ArrayList<>(loaded);
        }
    }

    private void persistRoles() throws IOException {
        Path path = repoRoot.resolve(CONFIG_DIR).resolve(ROLES_FILE);
        Files.createDirectories(path.getParent());
        Files.writeString(path, gson.toJson(roles));
    }

    private void persistUsers() throws IOException {
        Path path = repoRoot.resolve(CONFIG_DIR).resolve(USERS_FILE);
        Files.createDirectories(path.getParent());
        Files.writeString(path, gson.toJson(users));
    }

    // ── Admin enforcement ───────────────────────────────────────────────

    /**
     * Verifies that the given user has admin capability. Throws a
     * descriptive {@link SecurityException} if they do not.
     *
     * @param callerUserId the userId attempting the operation.
     * @param action       description of what they're trying to do (for the error message).
     */
    private void requireAdmin(String callerUserId, String action) {
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");
        if (!isAdmin(callerUserId)) {
            throw new SecurityException(
                    "Permission denied: user '" + callerUserId + "' is not an admin and cannot " + action + ". " +
                    "Contact a METHODOLOGIST or existing admin to request this change.");
        }
    }
}
