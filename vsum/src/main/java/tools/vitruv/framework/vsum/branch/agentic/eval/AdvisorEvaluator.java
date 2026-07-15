package tools.vitruv.framework.vsum.branch.agentic.eval;

import tools.vitruv.framework.vsum.branch.ConflictResolutionAdvisor;
import tools.vitruv.framework.vsum.branch.data.ResolutionProposal;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a {@link ConflictResolutionAdvisor} over a set of labelled
 * {@link EvalScenario}s and produces an {@link EvaluationReport}.
 *
 * <p>The evaluator is advisor-agnostic: it can score the cheap statistical
 * baseline ({@code AuditHistoryAdvisor}) entirely offline, and the local-LLM
 * {@link tools.vitruv.framework.vsum.branch.agentic.AgenticConflictResolutionAdvisor}
 * whenever an Ollama backend is running. Running both over the same scenarios
 * gives the head-to-head accuracy / coverage / calibration numbers the research
 * write-up needs.
 */
public final class AdvisorEvaluator {

    private AdvisorEvaluator() {
    }

    /**
     * Scores {@code advisor} over {@code scenarios}.
     *
     * @param advisorName label for the report (e.g. {@code "audit-history"} or {@code "agentic-llm"}).
     * @param advisor     the advisor under test.
     * @param scenarios   the labelled cases.
     * @return the aggregated report.
     */
    public static EvaluationReport evaluate(String advisorName, ConflictResolutionAdvisor advisor,
                                            List<EvalScenario> scenarios) {
        Objects.requireNonNull(advisorName, "advisorName must not be null");
        Objects.requireNonNull(advisor, "advisor must not be null");
        Objects.requireNonNull(scenarios, "scenarios must not be null");

        List<EvaluationReport.Row> rows = new ArrayList<>();
        for (EvalScenario scenario : scenarios) {
            long start = System.nanoTime();
            Optional<ResolutionProposal> proposal = advisor.propose(scenario.conflict());
            long latencyMillis = (System.nanoTime() - start) / 1_000_000L;

            if (proposal.isEmpty()) {
                rows.add(EvaluationReport.abstained(scenario.id(), scenario.expectedSide(), latencyMillis));
                continue;
            }

            String proposedSide = sideOf(scenario.conflict(), proposal.get());
            boolean correct = proposedSide != null && proposedSide.equals(scenario.expectedSide());
            rows.add(EvaluationReport.row(scenario.id(), scenario.expectedSide(),
                    proposedSide == null ? "unknown" : proposedSide,
                    proposal.get().confidence(), correct, latencyMillis));
        }
        return EvaluationReport.of(advisorName, rows);
    }

    private static String sideOf(UpdateConflict conflict, ResolutionProposal proposal) {
        if (proposal.chosenEntry() == conflict.getSourceEntry()) {
            return "source";
        }
        if (proposal.chosenEntry() == conflict.getTargetEntry()) {
            return "target";
        }
        return null;
    }
}
