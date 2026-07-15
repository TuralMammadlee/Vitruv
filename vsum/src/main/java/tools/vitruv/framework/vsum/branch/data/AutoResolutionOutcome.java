package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes the result of an automatic update-conflict resolution pass.
 *
 * <p>After a merge, each {@link UpdateConflict} is evaluated by
 * {@link tools.vitruv.framework.vsum.branch.storage.UpdateConflictResolver}
 * using a three-tier strategy:
 * <ol>
 *   <li><b>Origin rule</b> — if one side is ORIGINAL (human) and the other
 *       CONSEQUENTIAL (engine-generated), the ORIGINAL side wins without UI.</li>
 *   <li><b>Domain validator</b> — if a
 *       {@link tools.vitruv.framework.vsum.branch.DomainValidator} returns a
 *       preferred entry, that entry is applied without UI.</li>
 *   <li><b>Manual resolution</b> — any conflict not handled by either rule
 *       is placed in {@link #getUnresolved()} for UI-level handling.</li>
 * </ol>
 *
 * <p>Callers use {@link #isFullyResolved()} to decide whether the UI prompt
 * can be skipped entirely, and {@link #getUnresolved()} to feed the UI only
 * the conflicts that genuinely require human input.
 */
public final class AutoResolutionOutcome {

    /**
     * Captures the winning entry and the reason it was chosen for a single
     * auto-resolved conflict.
     */
    public record ResolvedConflict(
            UpdateConflict conflict,
            SemanticChangeEntry chosenEntry,
            String reason) {

        public ResolvedConflict {
            Objects.requireNonNull(conflict,    "conflict must not be null");
            Objects.requireNonNull(chosenEntry, "chosenEntry must not be null");
            Objects.requireNonNull(reason,      "reason must not be null");
        }
    }

    private final List<ResolvedConflict> autoResolved;
    private final List<UpdateConflict> unresolved;
    private final Map<UpdateConflict, ResolutionProposal> advisoryProposals;

    private AutoResolutionOutcome(List<ResolvedConflict> autoResolved,
                                   List<UpdateConflict> unresolved,
                                   Map<UpdateConflict, ResolutionProposal> advisoryProposals) {
        this.autoResolved      = List.copyOf(autoResolved);
        this.unresolved        = List.copyOf(unresolved);
        this.advisoryProposals = Map.copyOf(advisoryProposals);
    }

    /**
     * Creates an outcome with the given auto-resolved and unresolved lists and
     * no advisory proposals.
     */
    public static AutoResolutionOutcome of(List<ResolvedConflict> autoResolved,
                                            List<UpdateConflict> unresolved) {
        return of(autoResolved, unresolved, Map.of());
    }

    /**
     * Creates an outcome that also carries advisory proposals: advisor
     * suggestions for unresolved conflicts that were <em>not</em> applied
     * automatically (confidence below the threshold, or the reviewer deferred),
     * so the interactive tier can still show them to the human.
     */
    public static AutoResolutionOutcome of(List<ResolvedConflict> autoResolved,
                                            List<UpdateConflict> unresolved,
                                            Map<UpdateConflict, ResolutionProposal> advisoryProposals) {
        return new AutoResolutionOutcome(
                Objects.requireNonNull(autoResolved,      "autoResolved must not be null"),
                Objects.requireNonNull(unresolved,        "unresolved must not be null"),
                Objects.requireNonNull(advisoryProposals, "advisoryProposals must not be null"));
    }

    /**
     * Convenience factory for when there were no conflicts at all.
     */
    public static AutoResolutionOutcome empty() {
        return new AutoResolutionOutcome(List.of(), List.of(), Map.of());
    }

    /** Conflicts that were resolved automatically — no UI was needed. */
    public List<ResolvedConflict> getAutoResolved() { return autoResolved; }

    /** Conflicts that could not be resolved automatically and require UI. */
    public List<UpdateConflict> getUnresolved() { return unresolved; }

    /**
     * Returns the advisor's below-threshold (advisory) proposal for the given
     * unresolved conflict, if one exists. Interactive strategies display it as
     * a hint; it carries no authority.
     */
    public Optional<ResolutionProposal> getAdvisoryProposal(UpdateConflict conflict) {
        return Optional.ofNullable(advisoryProposals.get(conflict));
    }

    /** Returns {@code true} when every conflict was handled automatically. */
    public boolean isFullyResolved() { return unresolved.isEmpty(); }

    /** Returns {@code true} when at least one conflict still needs manual attention. */
    public boolean hasUnresolved() { return !unresolved.isEmpty(); }

    /** Total number of conflicts that entered the resolution pass. */
    public int totalCount() { return autoResolved.size() + unresolved.size(); }

    @Override
    public String toString() {
        return "AutoResolutionOutcome{autoResolved=" + autoResolved.size()
                + ", unresolved=" + unresolved.size() + "}";
    }
}
