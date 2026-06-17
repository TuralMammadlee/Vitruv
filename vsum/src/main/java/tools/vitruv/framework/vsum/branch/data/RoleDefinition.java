package tools.vitruv.framework.vsum.branch.data;

import java.util.Objects;

/**
 * Defines a named role with configurable merge-conflict resolution
 * permissions. Roles govern what severity of conflicts a user may resolve
 * and how many updates they are allowed to sacrifice in a single deletion.
 *
 * <p>Two built-in roles are provided via factory methods:
 * <ul>
 *   <li>{@link #methodologist()} — highest authority, unlimited merge power</li>
 *   <li>{@link #developer()} — standard developer, restricted to MEDIUM
 *       severity and a small number of lost updates</li>
 * </ul>
 *
 * <p>Custom roles can be created at runtime by an admin via
 * {@link tools.vitruv.framework.vsum.branch.storage.RoleManager}.
 *
 * @see ConflictSeverity
 * @see MergePolicy
 */
public class RoleDefinition {

    private final String name;
    private final int permissionLevel;
    private final int maxLostUpdates;
    private final ConflictSeverity maxSeverity;
    private final String description;
    private final boolean builtIn;

    /**
     * Creates a new role definition.
     *
     * @param name            unique role name (e.g. "METHODOLOGIST"), must not be blank.
     * @param permissionLevel numeric authority level, higher = more authority, must be &gt; 0.
     * @param maxLostUpdates  maximum number of updates this role may sacrifice in a
     *                        single deletion conflict. Use {@code -1} for unlimited.
     * @param maxSeverity     highest conflict severity this role may resolve.
     * @param description     human-readable description of the role.
     * @param builtIn         {@code true} if this is a system-provided role that
     *                        cannot be deleted.
     */
    public RoleDefinition(String name, int permissionLevel, int maxLostUpdates,
                          ConflictSeverity maxSeverity, String description, boolean builtIn) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name must not be null or blank");
        }
        if (permissionLevel <= 0) {
            throw new IllegalArgumentException("Permission level must be > 0, got " + permissionLevel);
        }
        if (maxLostUpdates < -1) {
            throw new IllegalArgumentException("maxLostUpdates must be >= -1, got " + maxLostUpdates);
        }
        this.name = name.toUpperCase();
        this.permissionLevel = permissionLevel;
        this.maxLostUpdates = maxLostUpdates;
        this.maxSeverity = Objects.requireNonNull(maxSeverity, "maxSeverity must not be null");
        this.description = description != null ? description : "";
        this.builtIn = builtIn;
    }

    /**
     * Creates the built-in METHODOLOGIST role with full authority.
     * Permission level 10, unlimited lost updates, CRITICAL severity.
     */
    public static RoleDefinition methodologist() {
        return new RoleDefinition(
                "METHODOLOGIST", 10, -1, ConflictSeverity.CRITICAL,
                "Designs consistency rules — full merge authority", true);
    }

    /**
     * Creates the built-in DEVELOPER role with restricted authority.
     * Permission level 5, max 3 lost updates, MEDIUM severity.
     */
    public static RoleDefinition developer() {
        return new RoleDefinition(
                "DEVELOPER", 5, 3, ConflictSeverity.MEDIUM,
                "Edits models — restricted merge permissions", true);
    }

    public String getName() { return name; }
    public int getPermissionLevel() { return permissionLevel; }
    public int getMaxLostUpdates() { return maxLostUpdates; }
    public ConflictSeverity getMaxSeverity() { return maxSeverity; }
    public String getDescription() { return description; }
    public boolean isBuiltIn() { return builtIn; }

    /**
     * Returns {@code true} if this role has unlimited deletion approval
     * (maxLostUpdates == -1).
     */
    public boolean hasUnlimitedDeletionApproval() {
        return maxLostUpdates == -1;
    }

    /**
     * Returns {@code true} if this role can approve a deletion that would
     * destroy the given number of updates. The check is inclusive: a role
     * with {@code maxLostUpdates == 3} can approve a deletion that destroys
     * exactly 3 updates.
     */
    public boolean canApproveUpdatesLost(int lostUpdateCount) {
        return hasUnlimitedDeletionApproval() || lostUpdateCount <= maxLostUpdates;
    }

    /**
     * Returns {@code true} if this role can resolve a conflict of the given
     * severity.
     */
    public boolean canResolveSeverity(ConflictSeverity severity) {
        return severity.isWithin(maxSeverity);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoleDefinition that = (RoleDefinition) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "RoleDefinition{" +
                "name='" + name + '\'' +
                ", level=" + permissionLevel +
                ", maxLostUpdates=" + (maxLostUpdates == -1 ? "unlimited" : maxLostUpdates) +
                ", maxSeverity=" + maxSeverity +
                ", builtIn=" + builtIn +
                '}';
    }
}
