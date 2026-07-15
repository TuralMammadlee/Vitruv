package tools.vitruv.framework.vsum.branch.agentic.eval;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.Objects;

/**
 * A labelled evaluation case: a concrete {@link UpdateConflict} together with the
 * side a competent reviewer would keep ({@code "source"} or {@code "target"}).
 *
 * <p>The label is the ground truth an advisor's proposal is scored against, in
 * the spirit of the "equivalent-to-developer" metric used by Merge-Bench and
 * MergeConflictBench, adapted here to Vitruvius's structured conflict model.
 */
public record EvalScenario(String id, UpdateConflict conflict, String expectedSide, String note) {

    public EvalScenario {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(conflict, "conflict must not be null");
        expectedSide = Objects.requireNonNull(expectedSide, "expectedSide must not be null")
                .trim().toLowerCase();
        if (!expectedSide.equals("source") && !expectedSide.equals("target")) {
            throw new IllegalArgumentException("expectedSide must be 'source' or 'target', got " + expectedSide);
        }
    }
}
