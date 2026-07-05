package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.ConflictReview;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.MergePolicy;
import tools.vitruv.framework.vsum.branch.data.OwnerDecision;

import java.io.Console;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Interactive owner review and decision step for the clearance-denied path
 * (activity-diagram boxes {@code review} and {@code d_owner}).
 *
 * <p>When the current user is a detected owner and a console is available,
 * presents the persisted review package and records an approve/deny decision.
 * On approve, the owner selects a resolution policy ({@code [R]/[D]/[S]}).
 */
public class OwnerEscalationResolver {

    private static final Logger LOGGER = LogManager.getLogger(OwnerEscalationResolver.class);

    private final OwnerDecisionStore decisionStore;

    public OwnerEscalationResolver(OwnerDecisionStore decisionStore) {
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore must not be null");
    }

    /**
     * If the current user is a detected owner and no decision exists yet,
     * runs an interactive review/decision session when a console is available.
     *
     * @return the decision recorded during this session, or empty if skipped.
     */
    public Optional<OwnerDecision> tryInteractiveDecision(DeletionConflict conflict,
                                                          ConflictReview review,
                                                          MergePolicy effectivePolicy,
                                                          String sourceBranch,
                                                          String targetBranch) throws IOException {
        Objects.requireNonNull(conflict, "conflict must not be null");
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(effectivePolicy, "effectivePolicy must not be null");

        if (!effectivePolicy.isCurrentUserDetectedOwner(conflict.getDetectedOwners())) {
            return Optional.empty();
        }

        Optional<OwnerDecision> existing = decisionStore.load(
                conflict.getDeletedElementUuid(), sourceBranch, targetBranch);
        if (existing.isPresent()) {
            return Optional.empty();
        }

        Console console = System.console();
        if (console == null) {
            LOGGER.debug("No console available; owner must submit decision out of band");
            return Optional.empty();
        }

        printReviewHeader(conflict, review, console);

        while (true) {
            String input = console.readLine("  Owner decision: [A] Approve  [D] Deny  [S] Skip (decide later) > ");
            if (input == null) {
                return Optional.empty();
            }
            input = input.trim().toUpperCase();
            switch (input) {
                case "A" -> {
                    DeletionPolicy chosen = promptResolutionPolicy(conflict, console);
                    String rationale = promptRationale(console);
                    OwnerDecision decision = OwnerDecision.of(
                            conflict.getDeletedElementUuid(),
                            effectivePolicy.getCurrentUserId(),
                            true,
                            rationale,
                            sourceBranch,
                            targetBranch,
                            chosen);
                    decisionStore.save(decision);
                    LOGGER.info("Owner {} approved with policy {} for element {}",
                            decision.getOwnerId(), chosen, conflict.getDeletedElementUuid());
                    return Optional.of(decision);
                }
                case "D" -> {
                    String rationale = promptRationale(console);
                    OwnerDecision decision = OwnerDecision.of(
                            conflict.getDeletedElementUuid(),
                            effectivePolicy.getCurrentUserId(),
                            false,
                            rationale,
                            sourceBranch,
                            targetBranch,
                            null);
                    decisionStore.save(decision);
                    LOGGER.info("Owner {} denied element {}", decision.getOwnerId(),
                            conflict.getDeletedElementUuid());
                    return Optional.of(decision);
                }
                case "S" -> {
                    console.printf("  Decision deferred — merge remains blocked until you decide.%n%n");
                    return Optional.empty();
                }
                default -> console.printf("  Invalid choice. Enter A, D, or S.%n");
            }
        }
    }

    private static void printReviewHeader(DeletionConflict conflict, ConflictReview review, Console console) {
        console.printf("%n========================================%n");
        console.printf("  OWNER REVIEW — DELETION CONFLICT%n");
        console.printf("========================================%n");
        console.printf("  Element: %s (%s)%n",
                conflict.getDeletedElementEClass() != null ? conflict.getDeletedElementEClass() : "unknown",
                conflict.getDeletedElementUuid());
        console.printf("  Deleted on: %s  |  Updates on: %s%n",
                conflict.getDeletingBranch(), conflict.getUpdatingBranch());
        console.printf("  Severity: %s  |  Lost updates: %d  |  Ancestor recovery: %s%n",
                review.getSeverityReport().getSeverity(),
                conflict.getLostUpdateCount(),
                review.isAncestorRecoverable() ? "yes" : "no");
        console.printf("  History entries: %d  |  State previews: %d%n%n",
                review.getHistory().size(), review.getStatePreviews().size());
    }

    private static DeletionPolicy promptResolutionPolicy(DeletionConflict conflict, Console console) {
        while (true) {
            StringBuilder prompt = new StringBuilder("  Resolution on approve: ");
            if (conflict.isAncestorAvailable()) {
                prompt.append("[R] Recover from ancestor  ");
            }
            prompt.append("[D] Accept deletion  ");
            prompt.append("[S] Skip (block deletion)  ");
            prompt.append("> ");

            String input = console.readLine(prompt.toString());
            if (input == null) {
                return DeletionPolicy.RESTRICT_DELETIONS;
            }
            input = input.trim().toUpperCase();
            switch (input) {
                case "R" -> {
                    if (conflict.isAncestorAvailable()) {
                        return DeletionPolicy.RECOVER_FROM_ANCESTOR;
                    }
                    console.printf("  Recovery unavailable (no shared ancestor).%n");
                }
                case "D" -> {
                    String confirm = console.readLine(
                            "  CONFIRM: destroys %d update(s). Type YES to confirm: ",
                            conflict.getLostUpdateCount());
                    if ("YES".equals(confirm != null ? confirm.trim() : "")) {
                        return DeletionPolicy.TOMBSTONE_WITH_WARNING;
                    }
                    console.printf("  Deletion not confirmed.%n");
                }
                case "S" -> {
                    return DeletionPolicy.RESTRICT_DELETIONS;
                }
                default -> console.printf("  Invalid choice.%n");
            }
        }
    }

    private static String promptRationale(Console console) {
        String raw = console.readLine("  Optional rationale (Enter to skip): ");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
