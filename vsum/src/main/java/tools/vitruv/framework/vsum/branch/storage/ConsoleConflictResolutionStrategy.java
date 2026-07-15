package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.ConflictResolutionStrategy;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.ManualResolution;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.io.Console;

/**
 * Interactive, severity-aware {@link ConflictResolutionStrategy} for update
 * conflicts that the automatic tiers could not resolve.
 *
 * <p>Behaviour by {@link ConflictSeverity}:
 * <ul>
 *   <li>{@link ConflictSeverity#LOW} / {@link ConflictSeverity#MEDIUM}: a quick
 *       accept-source / accept-target / skip choice. A rationale is optional.</li>
 *   <li>{@link ConflictSeverity#HIGH} / {@link ConflictSeverity#CRITICAL}: the
 *       full before/after values of both sides are printed for inspection and a
 *       non-empty rationale is <em>required</em> before a side can be accepted,
 *       so that high-impact decisions are always justified in the audit trail.</li>
 * </ul>
 *
 * <p><b>Advisor integration</b> (the activity diagram's LLM-assisted branch):
 * {@link #reviewProposal} presents a confident advisor proposal for the user to
 * confirm, override, or defer, and {@link #resolve(UpdateConflict, ResolutionProposal)}
 * shows a below-threshold proposal as a hint next to the normal choice.
 *
 * <p><b>Headless mode:</b> when {@link System#console()} is {@code null} (CI or
 * background server), every conflict is deferred rather than guessed, so the
 * unresolved list is returned to the caller untouched — mirroring
 * {@link DeletionConflictResolver}'s conservative headless behaviour. Confident
 * advisor proposals are the one exception: they are auto-confirmed headlessly
 * (matching the interface default), because the confidence threshold — not the
 * console — is their safety gate.
 */
public class ConsoleConflictResolutionStrategy implements ConflictResolutionStrategy {

    private static final Logger LOGGER = LogManager.getLogger(ConsoleConflictResolutionStrategy.class);

    @Override
    public ManualResolution resolve(UpdateConflict conflict) {
        return resolve(conflict, null);
    }

    @Override
    public ManualResolution resolve(UpdateConflict conflict, ResolutionProposal advisoryProposal) {
        Console console = System.console();
        if (console == null) {
            LOGGER.warn("No interactive console; deferring update conflict on {}.{}",
                    conflict.getEClass(), conflict.getFeatureName());
            return ManualResolution.deferred(conflict, "headless: no interactive console");
        }

        ConflictSeverity severity = conflict.getSeverity();
        boolean requiresInspection = severity == ConflictSeverity.HIGH
                || severity == ConflictSeverity.CRITICAL;

        printHeader(console, conflict, severity);
        if (requiresInspection) {
            printDetailedInspection(console, conflict);
        }
        if (advisoryProposal != null) {
            console.printf("  Advisor hint (below auto-apply threshold, %s, confidence %.2f):%n"
                            + "    would keep '%s' — %s%n",
                    advisoryProposal.source(), advisoryProposal.confidence(),
                    proposedBranch(conflict, advisoryProposal), advisoryProposal.rationale());
        }

        String prompt = "  Keep [S]ource '" + conflict.getSourceBranch()
                + "', [T]arget '" + conflict.getTargetBranch() + "', or s[K]ip? ";

        while (true) {
            String choice = readTrimmed(console, prompt).toUpperCase();
            switch (choice) {
                case "S" -> {
                    return accept(console, conflict, true, requiresInspection);
                }
                case "T" -> {
                    return accept(console, conflict, false, requiresInspection);
                }
                case "K", "" -> {
                    console.printf("  Skipped — conflict left unresolved.%n%n");
                    return ManualResolution.deferred(conflict, "user skipped");
                }
                default -> console.printf("  Please enter S, T, or K.%n");
            }
        }
    }

    /**
     * The "User Reviews LLM Proposal &amp; Confirms or Overrides" step: shows
     * the confident proposal and lets the user accept it, keep the other side
     * instead, or defer to full manual resolution.
     */
    @Override
    public ManualResolution reviewProposal(UpdateConflict conflict, ResolutionProposal proposal) {
        Console console = System.console();
        if (console == null) {
            LOGGER.info("Headless: auto-confirming advisor proposal for {}.{} (confidence {})",
                    conflict.getEClass(), conflict.getFeatureName(), proposal.confidence());
            return ConflictResolutionStrategy.super.reviewProposal(conflict, proposal);
        }

        boolean proposesSource = proposesSource(conflict, proposal);
        printHeader(console, conflict, conflict.getSeverity());
        console.printf("  Advisor proposal (%s, confidence %.2f): keep '%s'%n  Rationale: %s%n",
                proposal.source(), proposal.confidence(),
                proposedBranch(conflict, proposal), proposal.rationale());

        while (true) {
            String choice = readTrimmed(console,
                    "  [A]ccept proposal, keep [O]ther side, or [D]efer to manual? ").toUpperCase();
            switch (choice) {
                case "A", "" -> {
                    console.printf("  Proposal confirmed — kept branch '%s'.%n%n",
                            proposedBranch(conflict, proposal));
                    return proposesSource
                            ? ManualResolution.acceptSource(conflict, proposal.rationale())
                            : ManualResolution.acceptTarget(conflict, proposal.rationale());
                }
                case "O" -> {
                    return accept(console, conflict, !proposesSource, true);
                }
                case "D" -> {
                    console.printf("  Deferred — conflict routed to manual resolution.%n%n");
                    return ManualResolution.deferred(conflict, "user deferred advisor proposal");
                }
                default -> console.printf("  Please enter A, O, or D.%n");
            }
        }
    }

    private static boolean proposesSource(UpdateConflict conflict, ResolutionProposal proposal) {
        SemanticChangeEntry chosen = proposal.chosenEntry();
        if (chosen == conflict.getSourceEntry()) {
            return true;
        }
        if (chosen == conflict.getTargetEntry()) {
            return false;
        }
        return chosen.equals(conflict.getSourceEntry());
    }

    private static String proposedBranch(UpdateConflict conflict, ResolutionProposal proposal) {
        return proposesSource(conflict, proposal) ? conflict.getSourceBranch() : conflict.getTargetBranch();
    }

    private ManualResolution accept(Console console, UpdateConflict conflict,
                                    boolean source, boolean rationaleRequired) {
        String rationale = readTrimmed(console,
                rationaleRequired
                        ? "  Rationale (required for " + conflict.getSeverity() + " conflicts): "
                        : "  Optional rationale (press Enter to skip): ");

        if (rationaleRequired && rationale.isBlank()) {
            console.printf("  A rationale is required for %s conflicts. Please try again.%n",
                    conflict.getSeverity());
            return accept(console, conflict, source, true);
        }

        String annotation = rationale.isBlank() ? null : rationale;
        ManualResolution resolution = source
                ? ManualResolution.acceptSource(conflict, annotation)
                : ManualResolution.acceptTarget(conflict, annotation);
        console.printf("  Kept branch '%s'.%n%n", resolution.getChosenBranch());
        LOGGER.info("User resolved {}.{} -> {}", conflict.getEClass(),
                conflict.getFeatureName(), resolution.getChosenBranch());
        return resolution;
    }

    private void printHeader(Console console, UpdateConflict conflict, ConflictSeverity severity) {
        console.printf("%n--- Update conflict [%s] ---%n", severity);
        console.printf("  Element %s (uuid %s), feature '%s'%n",
                conflict.getEClass(), conflict.getElementUuid(), conflict.getFeatureName());
        console.printf("  Source '%s' [%s]  vs  target '%s' [%s]%n",
                conflict.getSourceBranch(), conflict.getSourceEntry().getOrigin(),
                conflict.getTargetBranch(), conflict.getTargetEntry().getOrigin());
    }

    private void printDetailedInspection(Console console, UpdateConflict conflict) {
        console.printf("  !! High-impact conflict — review both changes carefully.%n");
        console.printf("     Source change: %s%n", conflict.getSourceEntry());
        console.printf("     Target change: %s%n", conflict.getTargetEntry());
    }

    private String readTrimmed(Console console, String prompt) {
        String raw = console.readLine(prompt);
        return raw == null ? "" : raw.trim();
    }
}
