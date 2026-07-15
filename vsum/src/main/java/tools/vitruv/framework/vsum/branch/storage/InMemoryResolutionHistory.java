package tools.vitruv.framework.vsum.branch.storage;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple in-memory {@link ResolutionHistory} that tallies how often each side
 * was kept, keyed by {@link ResolutionHistory#signature(UpdateConflict)}.
 *
 * <p>Intended for tests and single-session use. A production deployment would
 * replace this with an implementation that reads the persisted audit log so the
 * evidence survives restarts and is shared across merges.
 */
public final class InMemoryResolutionHistory implements ResolutionHistory {

    private final Map<String, int[]> tallies = new HashMap<>();

    /** Records that the source side was kept for a conflict like this one. */
    public InMemoryResolutionHistory recordSourceWin(UpdateConflict conflict) {
        bump(ResolutionHistory.signature(conflict), 0);
        return this;
    }

    /** Records that the target side was kept for a conflict like this one. */
    public InMemoryResolutionHistory recordTargetWin(UpdateConflict conflict) {
        bump(ResolutionHistory.signature(conflict), 1);
        return this;
    }

    /** Records a decision under an explicit signature (e.g. when replaying an audit file). */
    public InMemoryResolutionHistory record(String signature, boolean sourceWon) {
        bump(Objects.requireNonNull(signature, "signature must not be null"), sourceWon ? 0 : 1);
        return this;
    }

    private void bump(String signature, int index) {
        int[] counts = tallies.computeIfAbsent(signature, k -> new int[2]);
        counts[index]++;
    }

    @Override
    public Outcomes outcomesFor(UpdateConflict conflict) {
        int[] counts = tallies.get(ResolutionHistory.signature(conflict));
        if (counts == null) {
            return Outcomes.none();
        }
        return new Outcomes(counts[0], counts[1]);
    }
}
