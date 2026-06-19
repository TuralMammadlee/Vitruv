package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;

import java.io.Console;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves delete-vs-update conflicts using an interactive CLI or falls back
 * to an automatic policy when running in a headless (non-interactive)
 * environment (CI/CD, background server).
 *
 * <p>
 * <b>Interactive mode:</b> For each {@link DeletionConflict}, the resolver
 * prints a descriptive warning and offers choices to the user:
 * <ul>
 * <li>{@code [R]} Recover from ancestor (if available)</li>
 * <li>{@code [D]} Accept deletion (with an explicit count of lost updates)</li>
 * <li>{@code [S]} Skip (keep both, resolve later)</li>
 * </ul>
 *
 * <p>
 * <b>Headless mode:</b> When {@link System#console()} is {@code null} (no
 * interactive terminal), the resolver automatically falls back to
 * {@link DeletionPolicy#RESTRICT_DELETIONS} to prevent silent data loss in
 * CI/CD pipelines.
 *
 * <p>
 * <b>User-role guardrails:</b> A {@link MergePolicy} determines whether the
 * current user is allowed to approve high-impact deletions. Junior developers
 * are blocked from approving deletions that exceed the high-impact threshold.
 *
 * @see DeletionConflict
 * @see DeletionConflictAnalyzer
 * @see MergePolicy
 */
public class DeletionConflictResolver {

    private static final Logger LOGGER = LogManager.getLogger(DeletionConflictResolver.class);

    private final MergePolicy mergePolicy;

    /**
     * Creates a resolver with the given merge policy.
     *
     * @param mergePolicy the policy governing deletion approval and defaults.
     */
    public DeletionConflictResolver(MergePolicy mergePolicy) {
        this.mergePolicy = Objects.requireNonNull(mergePolicy, "mergePolicy must not be null");
    }

    /**
     * Represents the user's (or system's) chosen resolution for a single
     * deletion conflict.
     */
    public static class Resolution {
        private final DeletionConflict conflict;
        private final DeletionPolicy chosenPolicy;
        private final String reason;
        private final String rationale;

        public Resolution(DeletionConflict conflict, DeletionPolicy chosenPolicy, String reason) {
            this(conflict, chosenPolicy, reason, null);
        }

        public Resolution(DeletionConflict conflict, DeletionPolicy chosenPolicy, String reason, String rationale) {
            this.conflict = conflict;
            this.chosenPolicy = chosenPolicy;
            this.reason = reason;
            this.rationale = rationale;
        }

        public DeletionConflict getConflict() {
            return conflict;
        }

        public DeletionPolicy getChosenPolicy() {
            return chosenPolicy;
        }

        public String getReason() {
            return reason;
        }

        /**
         * Returns the optional free-text annotation entered by the human resolver,
         * or {@code null} when the decision was made headlessly or the user
         * pressed Enter without typing anything.
         */
        public String getRationale() {
            return rationale;
        }

        @Override
        public String toString() {
            return "Resolution{policy=" + chosenPolicy + ", reason='" + reason + "'"
                    + (rationale != null ? ", rationale='" + rationale + "'" : "") + '}';
        }
    }

    /**
     * Resolves a list of deletion conflicts, either interactively or
     * automatically depending on whether a console is available.
     *
     * @param conflicts the conflicts to resolve.
     * @return a list of resolutions, one per conflict.
     */
    public List<Resolution> resolve(List<DeletionConflict> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return List.of();
        }

        Console console = System.console();
        boolean interactive = console != null;

        if (!interactive) {
            DeletionPolicy fallback = mergePolicy.getDefaultDeletionPolicy();
            LOGGER.warn("No interactive console detected (CI/CD or headless mode). "
                    + "Falling back to policy-default '{}' for all {} conflict(s).",
                    fallback, conflicts.size());
            return resolveHeadless(conflicts, fallback);
        }

        return resolveInteractive(conflicts, console);
    }

    /**
     * Headless resolution: apply the merge policy's configured default policy
     * to every conflict so that CI / non-interactive environments behave
     * deterministically according to project configuration.
     */
    private List<Resolution> resolveHeadless(List<DeletionConflict> conflicts, DeletionPolicy fallback) {
        List<Resolution> resolutions = new ArrayList<>();
        String reason = "Non-interactive environment: applied policy default '" + fallback + "'";
        for (DeletionConflict conflict : conflicts) {
            resolutions.add(new Resolution(conflict, fallback, reason));
        }
        return resolutions;
    }

    /**
     * Interactive resolution: present each conflict to the user in the CLI.
     */
    private List<Resolution> resolveInteractive(List<DeletionConflict> conflicts, Console console) {
        List<Resolution> resolutions = new ArrayList<>();

        console.printf("%n========================================%n");
        console.printf("  DELETION CONFLICT RESOLUTION%n");
        console.printf("  %d conflict(s) detected%n", conflicts.size());
        console.printf("========================================%n%n");

        int index = 1;
        for (DeletionConflict conflict : conflicts) {
            console.printf("--- Conflict %d of %d ---%n", index, conflicts.size());
            printConflictWarning(conflict, console);
            printOwnerAssignmentContext(conflict, console);

            // Check user-role guardrails
            if (!mergePolicy.canApproveDeletion(conflict)) {
                console.printf("  Your role (%s) does not permit approving this %s-severity deletion.%n",
                        mergePolicy.getRoleName(), conflict.getSeverity(mergePolicy.getSeverityThresholds()));
                console.printf("     Escalation required.  Deletion is BLOCKED.%n%n");
                resolutions.add(new Resolution(conflict, DeletionPolicy.RESTRICT_DELETIONS,
                        "Blocked: role " + mergePolicy.getRoleName() + " cannot approve " + conflict.getSeverity(mergePolicy.getSeverityThresholds()) + " deletion"));
                index++;
                continue;
            }

            // Offer choices
            Resolution resolution = promptUserChoice(conflict, console);
            resolutions.add(resolution);
            LOGGER.info("Conflict {} resolved with policy: {}", index, resolution.getChosenPolicy());
            index++;
        }

        console.printf("========================================%n");
        console.printf("  All conflicts resolved.%n");
        console.printf("========================================%n%n");

        return resolutions;
    }

    /**
     * Prompts the user for an optional free-text rationale/annotation.
     * Returns the trimmed input, or {@code null} if the user pressed Enter
     * without typing anything (or if EOF was reached).
     */
    private String promptRationale(Console console) {
        String raw = console.readLine("  Optional rationale (press Enter to skip): ");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * Prints whether the current decision will follow the owner-priority path or
     * fall back to role-based clearance, based on blame-derived ownership metadata.
     */
    private void printOwnerAssignmentContext(DeletionConflict conflict, Console console) {
        if (!conflict.isOwnerDetectionAvailable()) {
            console.printf("  Owner detection unavailable. Applying role-based clearance.%n%n");
            LOGGER.info("Owner detection unavailable; applying role-based clearance");
            return;
        }

        Set<String> owners = conflict.getDetectedOwners();
        if (owners.isEmpty()) {
            console.printf("  No owner detected for this conflict. Applying role-based clearance.%n%n");
            LOGGER.info("No owner detected; applying role-based clearance");
            return;
        }

        console.printf("  Detected owner(s): %s%n", String.join(", ", owners));
        if (mergePolicy.isCurrentUserDetectedOwner(owners)) {
            console.printf("  You are a detected owner. Owner-priority resolution rights apply.%n%n");
            LOGGER.info("Owner-priority resolution applies for detected owner(s): {}", owners);
        } else {
            console.printf("  You are not a detected owner. Role-based clearance (%s) will be used.%n%n",
                    mergePolicy.getRoleName());
            LOGGER.info("Current user is not a detected owner; applying role-based clearance ({})",
                    mergePolicy.getRoleName());
        }
    }

    /**
     * Prints a detailed warning about a deletion conflict.
     */
    private void printConflictWarning(DeletionConflict conflict, Console console) {
        console.printf("%n   WARNING: Branch '%s' deleted [%s] (uuid: %s)%n",
                conflict.getDeletingBranch(),
                conflict.getDeletedElementEClass() != null ? conflict.getDeletedElementEClass() : "unknown type",
                conflict.getDeletedElementUuid());
        console.printf("  But branch '%s' made %d update(s) inside it.%n",
                conflict.getUpdatingBranch(),
                conflict.getLostUpdateCount());
        console.printf("  Accepting this deletion will DESTROY those %d change(s).%n%n",
                conflict.getLostUpdateCount());

        // Extra warning when a consequential deletion conflicts with original updates
        if (conflict.isConsequentialDeletionVsOriginalUpdates()) {
            console.printf("   NOTE: The deletion was ENGINE-GENERATED (consequential), but the%n");
            console.printf("     conflicting updates were HUMAN-MADE (original).%n");
            console.printf("     Vitruvius rule: original changes are preferred. Recovery is RECOMMENDED.%n%n");
        }

        // List affected updates (summary, not full detail)
        console.printf("  Affected updates:%n");
        int shown = 0;
        for (SemanticChangeEntry entry : conflict.getAffectedUpdates()) {
            if (shown >= 5) {
                console.printf("     ... and %d more%n",
                        conflict.getLostUpdateCount() - shown);
                break;
            }
            console.printf("     #%d  %s on '%s' [%s]%n",
                    entry.getIndex(),
                    entry.getChangeType(),
                    entry.getFeature() != null ? entry.getFeature() : "(lifecycle)",
                    entry.getOrigin());
            shown++;
        }
        console.printf("%n");
    }

    /**
     * Prompts the user for a resolution choice.
     */
    private Resolution promptUserChoice(DeletionConflict conflict, Console console) {
        while (true) {
            // Build the prompt dynamically based on what's available
            StringBuilder prompt = new StringBuilder("  Choose: ");
            if (conflict.isAncestorAvailable()) {
                prompt.append("[R] Recover from ancestor  ");
            }
            prompt.append("[D] Accept deletion  ");
            prompt.append("[S] Skip (resolve later)  ");
            prompt.append("> ");

            String input = console.readLine(prompt.toString());
            if (input == null) {
                // EOF — treat as skip
                return new Resolution(conflict, DeletionPolicy.RESTRICT_DELETIONS,
                        "EOF received, deletion blocked");
            }

            input = input.trim().toUpperCase();
            switch (input) {
                case "R":
                    if (conflict.isAncestorAvailable()) {
                        return new Resolution(conflict, DeletionPolicy.RECOVER_FROM_ANCESTOR,
                                "User chose to recover from shared ancestor",
                                promptRationale(console));
                    }
                    console.printf("  Recovery is not available (no shared ancestor). Please choose another option.%n");
                    break;

                case "D":
                    // Confirm destructive action
                    String confirm = console.readLine(
                            "   CONFIRM: This will destroy %d update(s). Type 'YES' to confirm: ",
                            conflict.getLostUpdateCount());
                    if ("YES".equals(confirm != null ? confirm.trim() : "")) {
                        return new Resolution(conflict, DeletionPolicy.TOMBSTONE_WITH_WARNING,
                                "User confirmed deletion, " + conflict.getLostUpdateCount()
                                        + " update(s) will be lost",
                                promptRationale(console));
                    }
                    console.printf("  Deletion not confirmed. Please choose again.%n");
                    break;

                case "S":
                    return new Resolution(conflict, DeletionPolicy.RESTRICT_DELETIONS,
                            "User chose to skip — deletion blocked, manual resolution required",
                            promptRationale(console));

                default:
                    console.printf("  Invalid choice '%s'. Please enter R, D, or S.%n", input);
            }
        }
    }
}
