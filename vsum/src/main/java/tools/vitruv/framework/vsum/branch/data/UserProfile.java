package tools.vitruv.framework.vsum.branch.data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Maps a user identity (Git email) to a {@link RoleDefinition} and tracks
 * whether the user has admin capability.
 *
 * <p>Admin capability is orthogonal to the domain role:
 * <ul>
 *   <li>A METHODOLOGIST is automatically an admin (highest domain authority)</li>
 *   <li>A DEVELOPER can also be granted admin by another admin</li>
 *   <li>Admin capability allows managing roles and users, not bypassing
 *       merge restrictions — those are governed by the role itself</li>
 * </ul>
 *
 * <p>Each profile includes an audit trail: who assigned it and when.
 *
 * @see RoleDefinition
 * @see tools.vitruv.framework.vsum.branch.storage.RoleManager
 */
public class UserProfile {

    private final String userId;
    private String roleName;
    private boolean admin;
    private String assignedBy;
    private String assignedAt;

    /**
     * Creates a new user profile.
     *
     * @param userId     the user's Git email (from {@code git config user.email}),
     *                   must not be null or blank.
     * @param roleName   name of the {@link RoleDefinition} assigned to this user.
     * @param admin      whether this user has admin capability.
     * @param assignedBy userId of the admin who created this assignment.
     * @param assignedAt ISO-8601 timestamp of the assignment.
     */
    public UserProfile(String userId, String roleName, boolean admin,
                       String assignedBy, String assignedAt) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("roleName must not be null or blank");
        }
        this.userId = userId.toLowerCase();
        this.roleName = roleName.toUpperCase();
        this.admin = admin;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }

    /**
     * Creates a profile with the current timestamp.
     */
    public static UserProfile create(String userId, String roleName, boolean admin, String assignedBy) {
        return new UserProfile(userId, roleName, admin, assignedBy,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    public String getUserId() { return userId; }
    public String getRoleName() { return roleName; }
    public boolean isAdmin() { return admin; }
    public String getAssignedBy() { return assignedBy; }
    public String getAssignedAt() { return assignedAt; }

    /**
     * Updates the role assignment. Called by RoleManager when an admin
     * reassigns a user.
     */
    public void setRoleName(String roleName, String assignedBy) {
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("roleName must not be null or blank");
        }
        this.roleName = roleName.toUpperCase();
        this.assignedBy = assignedBy;
        this.assignedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * Grants admin capability to this user.
     */
    public void grantAdmin(String grantedBy) {
        this.admin = true;
        this.assignedBy = grantedBy;
        this.assignedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * Revokes admin capability from this user.
     */
    public void revokeAdmin(String revokedBy) {
        this.admin = false;
        this.assignedBy = revokedBy;
        this.assignedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserProfile that = (UserProfile) o;
        return userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    @Override
    public String toString() {
        return "UserProfile{" +
                "userId='" + userId + '\'' +
                ", role='" + roleName + '\'' +
                ", admin=" + admin +
                ", assignedBy='" + assignedBy + '\'' +
                '}';
    }
}
