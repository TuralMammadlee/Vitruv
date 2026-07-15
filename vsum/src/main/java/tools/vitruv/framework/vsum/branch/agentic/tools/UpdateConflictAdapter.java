package tools.vitruv.framework.vsum.branch.agentic.tools;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolution;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolutionProvider;
import tools.vitruv.framework.vsum.branch.merge.MergeConflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bridges the production conflict model ({@link UpdateConflict}, which uses
 * {@code SemanticChangeEntry} and human-facing source/target branch naming) to
 * the replay merge engine's model ({@link MergeConflict} plus
 * {@link ConflictResolution}'s OURS/THEIRS choice), so that a candidate decision
 * for a single production conflict can be tried out inside the sandboxed
 * {@code SemanticMergeEngine}.
 *
 * <p>Choice mapping (per {@link ConflictResolutionProvider}'s contract): OURS
 * keeps the target branch's value, THEIRS accepts the source branch's value.
 * Therefore choosing the <em>source</em> side maps to THEIRS and choosing the
 * <em>target</em> side maps to OURS.
 *
 * <p>The two schemas evolved independently and are matched only by element UUID
 * (and feature, when the engine populates it). The engine may surface additional
 * conflicts for other elements during a replay; those are resolved with a fixed,
 * neutral OURS baseline so the simulation isolates the effect of the one decision
 * under test rather than conflating it with unrelated choices.
 */
public final class UpdateConflictAdapter {

    private UpdateConflictAdapter() {
    }

    /**
     * Builds a {@link ConflictResolutionProvider} that applies the candidate
     * choice to the merge conflict(s) matching {@code focus} and a neutral OURS
     * baseline to every other conflict.
     *
     * @param focus        the production conflict whose resolution is under test.
     * @param chooseSource {@code true} to keep the source side (THEIRS), {@code false} for target (OURS).
     * @return a provider suitable for {@code new SemanticMergeEngine(..., provider)}.
     */
    public static ConflictResolutionProvider providerFor(UpdateConflict focus, boolean chooseSource) {
        Objects.requireNonNull(focus, "focus must not be null");
        ConflictResolution.Choice focusChoice =
                chooseSource ? ConflictResolution.Choice.THEIRS : ConflictResolution.Choice.OURS;
        return conflicts -> {
            List<ConflictResolution> resolutions = new ArrayList<>(conflicts.size());
            for (MergeConflict conflict : conflicts) {
                ConflictResolution.Choice choice =
                        matches(focus, conflict) ? focusChoice : ConflictResolution.Choice.OURS;
                resolutions.add(new ConflictResolution(conflict.getElementId(), choice));
            }
            return resolutions;
        };
    }

    private static boolean matches(UpdateConflict focus, MergeConflict conflict) {
        String focusUuid = focus.getElementUuid();
        boolean uuidMatches = Objects.equals(focusUuid, conflict.getElementUuid())
                || Objects.equals(focusUuid, conflict.getElementId());
        if (!uuidMatches) {
            return false;
        }
        String feature = conflict.getConflictingFeature();
        // When the engine records a feature, require it to match too; otherwise
        // fall back to UUID-only matching (deletion conflicts carry no feature).
        return feature == null || Objects.equals(feature, focus.getFeatureName());
    }
}
