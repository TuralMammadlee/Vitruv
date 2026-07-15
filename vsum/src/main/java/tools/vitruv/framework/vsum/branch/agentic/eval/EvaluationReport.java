package tools.vitruv.framework.vsum.branch.agentic.eval;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated outcome of evaluating one advisor over a set of {@link EvalScenario}s,
 * with per-case rows and summary metrics: coverage (fraction on which the advisor
 * offered a proposal), accuracy over covered cases, mean latency, and a coarse
 * confidence-calibration table (binned confidence versus empirical accuracy).
 *
 * <p>Renders to both JSON and CSV so results can be dropped straight into a
 * report or a spreadsheet.
 */
public final class EvaluationReport {

    /** One scored case. */
    public static final class Row {
        final String scenarioId;
        final String expectedSide;
        final String proposedSide; // null when the advisor abstained
        final Double confidence;   // null when abstained
        final Boolean correct;     // null when abstained
        final long latencyMillis;

        Row(String scenarioId, String expectedSide, String proposedSide,
            Double confidence, Boolean correct, long latencyMillis) {
            this.scenarioId = scenarioId;
            this.expectedSide = expectedSide;
            this.proposedSide = proposedSide;
            this.confidence = confidence;
            this.correct = correct;
            this.latencyMillis = latencyMillis;
        }
    }

    /** One confidence bin for the calibration table. */
    public static final class CalibrationBin {
        final String range;
        final int count;
        final double meanConfidence;
        final double empiricalAccuracy;

        CalibrationBin(String range, int count, double meanConfidence, double empiricalAccuracy) {
            this.range = range;
            this.count = count;
            this.meanConfidence = meanConfidence;
            this.empiricalAccuracy = empiricalAccuracy;
        }
    }

    private final String advisorName;
    private final int totalScenarios;
    private final int covered;
    private final int correct;
    private final double coverage;
    private final double accuracy;
    private final double meanLatencyMillis;
    private final List<CalibrationBin> calibration;
    private final List<Row> rows;

    private EvaluationReport(String advisorName, List<Row> rows) {
        this.advisorName = advisorName;
        this.rows = List.copyOf(rows);
        this.totalScenarios = rows.size();

        int coveredCount = 0;
        int correctCount = 0;
        long latencySum = 0;
        for (Row row : rows) {
            latencySum += row.latencyMillis;
            if (row.proposedSide != null) {
                coveredCount++;
                if (Boolean.TRUE.equals(row.correct)) {
                    correctCount++;
                }
            }
        }
        this.covered = coveredCount;
        this.correct = correctCount;
        this.coverage = totalScenarios == 0 ? 0.0 : (double) coveredCount / totalScenarios;
        this.accuracy = coveredCount == 0 ? 0.0 : (double) correctCount / coveredCount;
        this.meanLatencyMillis = totalScenarios == 0 ? 0.0 : (double) latencySum / totalScenarios;
        this.calibration = computeCalibration(rows);
    }

    /** Builds a report from raw scored rows. */
    public static EvaluationReport of(String advisorName, List<Row> rows) {
        return new EvaluationReport(advisorName, rows);
    }

    /** Factory for a scored row (covered case). */
    public static Row row(String scenarioId, String expectedSide, String proposedSide,
                          double confidence, boolean correct, long latencyMillis) {
        return new Row(scenarioId, expectedSide, proposedSide, confidence, correct, latencyMillis);
    }

    /** Factory for an abstention row (advisor gave no opinion). */
    public static Row abstained(String scenarioId, String expectedSide, long latencyMillis) {
        return new Row(scenarioId, expectedSide, null, null, null, latencyMillis);
    }

    private static List<CalibrationBin> computeCalibration(List<Row> rows) {
        double[] edges = {0.0, 0.5, 0.7, 0.9, 1.0001};
        String[] labels = {"[0.0,0.5)", "[0.5,0.7)", "[0.7,0.9)", "[0.9,1.0]"};
        int[] counts = new int[labels.length];
        double[] confSums = new double[labels.length];
        int[] correctCounts = new int[labels.length];

        for (Row row : rows) {
            if (row.proposedSide == null || row.confidence == null) {
                continue;
            }
            for (int b = 0; b < labels.length; b++) {
                if (row.confidence >= edges[b] && row.confidence < edges[b + 1]) {
                    counts[b]++;
                    confSums[b] += row.confidence;
                    if (Boolean.TRUE.equals(row.correct)) {
                        correctCounts[b]++;
                    }
                    break;
                }
            }
        }

        List<CalibrationBin> bins = new ArrayList<>();
        for (int b = 0; b < labels.length; b++) {
            if (counts[b] == 0) {
                continue;
            }
            bins.add(new CalibrationBin(labels[b], counts[b],
                    round(confSums[b] / counts[b]),
                    round((double) correctCounts[b] / counts[b])));
        }
        return bins;
    }

    public String getAdvisorName() { return advisorName; }
    public int getTotalScenarios() { return totalScenarios; }
    public int getCovered() { return covered; }
    public int getCorrect() { return correct; }
    public double getCoverage() { return coverage; }
    public double getAccuracy() { return accuracy; }
    public double getMeanLatencyMillis() { return meanLatencyMillis; }
    public List<Row> getRows() { return rows; }

    /** Pretty-printed JSON view of the whole report. */
    public String toJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        return gson.toJson(this);
    }

    /** CSV of per-case rows: scenario, expected, proposed, confidence, correct, latencyMs. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder("scenario,expected,proposed,confidence,correct,latencyMs\n");
        for (Row row : rows) {
            sb.append(row.scenarioId).append(',')
              .append(row.expectedSide).append(',')
              .append(row.proposedSide == null ? "ABSTAIN" : row.proposedSide).append(',')
              .append(row.confidence == null ? "" : String.valueOf(row.confidence)).append(',')
              .append(row.correct == null ? "" : String.valueOf(row.correct)).append(',')
              .append(row.latencyMillis).append('\n');
        }
        return sb.toString();
    }

    /** One-line human-readable summary. */
    public String summaryLine() {
        return String.format(
                "%s: coverage=%.0f%% (%d/%d), accuracy=%.0f%% (%d/%d), meanLatency=%.0fms",
                advisorName, coverage * 100, covered, totalScenarios,
                accuracy * 100, correct, covered, meanLatencyMillis);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
