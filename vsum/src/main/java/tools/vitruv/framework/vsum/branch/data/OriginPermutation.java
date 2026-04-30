package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;

/**
 * Classifies an update conflict by the {@link ChangeOrigin} of both sides.
 *
 * <p>Used by downstream severity calculation and auto-resolution logic to
 * decide whether the Vitruvius rule "original (human) over consequential
 * (engine-generated)" can be applied automatically.
 *
 * <ul>
 *   <li>{@link #O_O} — both sides are human edits. No automatic preference.</li>
 *   <li>{@link #O_C} — source is human, target is engine-generated.
 *       Auto-resolvable in favor of source.</li>
 *   <li>{@link #C_O} — source is engine-generated, target is human.
 *       Auto-resolvable in favor of target.</li>
 *   <li>{@link #C_C} — both sides are engine-generated. Likely a
 *       consistency-rule collision; treated as higher risk.</li>
 *   <li>{@link #UNKNOWN_UNKNOWN} — origin could not be determined for at
 *       least one side. Treated conservatively (no auto-resolution).</li>
 * </ul>
 */
public enum OriginPermutation {
    O_O,
    O_C,
    C_O,
    C_C,
    UNKNOWN_UNKNOWN;

    /**
     * Computes the permutation from two change origins. Any combination
     * involving {@link ChangeOrigin#UNKNOWN} collapses to
     * {@link #UNKNOWN_UNKNOWN} so callers cannot accidentally treat it as
     * auto-resolvable.
     */
    public static OriginPermutation of(ChangeOrigin source, ChangeOrigin target) {
        if (source == null || target == null
                || source == ChangeOrigin.UNKNOWN
                || target == ChangeOrigin.UNKNOWN) {
            return UNKNOWN_UNKNOWN;
        }
        if (source == ChangeOrigin.ORIGINAL && target == ChangeOrigin.ORIGINAL) {
            return O_O;
        }
        if (source == ChangeOrigin.ORIGINAL && target == ChangeOrigin.CONSEQUENTIAL) {
            return O_C;
        }
        if (source == ChangeOrigin.CONSEQUENTIAL && target == ChangeOrigin.ORIGINAL) {
            return C_O;
        }
        return C_C;
    }

    /**
     * Returns {@code true} when exactly one side is ORIGINAL (human) and the
     * other is CONSEQUENTIAL (engine). Used as the trigger for the
     * "favor human change" auto-resolution path.
     */
    public boolean isMixedOrigin() {
        return this == O_C || this == C_O;
    }
}
