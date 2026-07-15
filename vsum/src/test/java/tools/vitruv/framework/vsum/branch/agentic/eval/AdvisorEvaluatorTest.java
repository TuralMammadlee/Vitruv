package tools.vitruv.framework.vsum.branch.agentic.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.storage.AuditHistoryAdvisor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the offline evaluation harness: the built-in scenario library
 * and the head-to-head metrics computed by {@link AdvisorEvaluator} /
 * {@link EvaluationReport}.
 */
class AdvisorEvaluatorTest {

    @Test
    @DisplayName("the audit-history baseline solves only the seeded convention cases")
    void baselineCoversConventionCasesOnly() {
        ScenarioLibrary library = ScenarioLibrary.create();
        List<EvalScenario> scenarios = library.scenarios();

        EvaluationReport report = AdvisorEvaluator.evaluate(
                "audit-history", new AuditHistoryAdvisor(library.history()), scenarios);

        // The two O_O convention scenarios have seeded history; the four mixed-origin
        // ones do not, so the cold-start guard makes the baseline abstain on those.
        assertEquals(2, report.getCovered());
        assertEquals(2, report.getCorrect());
        assertEquals(1.0, report.getAccuracy(), 1e-9);
        assertTrue(report.getCoverage() < 1.0);
    }

    @Test
    @DisplayName("report renders CSV with one row per scenario")
    void reportRendersCsv() {
        ScenarioLibrary library = ScenarioLibrary.create();
        List<EvalScenario> scenarios = library.scenarios();
        EvaluationReport report = AdvisorEvaluator.evaluate(
                "audit-history", new AuditHistoryAdvisor(library.history()), scenarios);

        String csv = report.toCsv();
        long dataRows = csv.lines().count() - 1; // minus header
        assertEquals(scenarios.size(), dataRows);
        assertTrue(csv.startsWith("scenario,expected,proposed,confidence,correct,latencyMs"));
    }

    @Test
    @DisplayName("scenario library is internally consistent")
    void scenarioLibraryConsistent() {
        ScenarioLibrary library = ScenarioLibrary.create();
        assertEquals(6, library.scenarios().size());
        // Every scenario carries a valid label (constructor enforces source/target).
        library.scenarios().forEach(s -> assertTrue(
                s.expectedSide().equals("source") || s.expectedSide().equals("target")));
    }
}
