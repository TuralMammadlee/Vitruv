package tools.vitruv.framework.vsum.branch.agentic;

import java.util.ArrayList;
import java.util.List;

/**
 * Full, replayable record of one agentic {@code propose(...)} run: the model
 * used, every reasoning turn, every tool call with its arguments and result, and
 * the final decision (or the reason the advisor gave no opinion).
 *
 * <p>Persisting this alongside the existing {@code .vitruvius/audit/*.audit.json}
 * files keeps every auto-resolution the agent influences fully explainable and
 * citable after the fact, which is essential both for the human reviewer and for
 * the research evaluation.
 *
 * <p>Instances are populated incrementally by
 * {@link AgenticConflictResolutionAdvisor} and are not thread-safe; each
 * {@code propose} call owns its own trace.
 */
public final class AgenticTrace {

    /** How a run ended. */
    public enum Outcome {
        /** A resolution was proposed (see {@code chosenSide}/{@code confidence}). */
        RESOLVED,
        /** The model explicitly declined to choose a side. */
        NO_OPINION,
        /** The backend was unreachable or errored. */
        ERROR,
        /** The wall-clock deadline was hit before a decision. */
        TIMEOUT,
        /** The iteration budget was exhausted without a decision. */
        EXHAUSTED,
        /** The advisor was disabled by configuration. */
        DISABLED
    }

    private final String conflictSignature;
    private final String elementUuid;
    private final String feature;
    private final String model;
    private final List<Step> steps = new ArrayList<>();

    private Outcome outcome;
    private String chosenSide;
    private Double confidence;
    private String rationale;
    private long elapsedMillis;

    public AgenticTrace(String conflictSignature, String elementUuid, String feature, String model) {
        this.conflictSignature = conflictSignature;
        this.elementUuid = elementUuid;
        this.feature = feature;
        this.model = model;
    }

    /** Records one model turn: its free-text content and any tool calls it made. */
    public Step addStep(int iteration, String assistantContent) {
        Step step = new Step(iteration, assistantContent);
        steps.add(step);
        return step;
    }

    public void finish(Outcome outcome, String chosenSide, Double confidence,
                       String rationale, long elapsedMillis) {
        this.outcome = outcome;
        this.chosenSide = chosenSide;
        this.confidence = confidence;
        this.rationale = rationale;
        this.elapsedMillis = elapsedMillis;
    }

    public String getConflictSignature() { return conflictSignature; }
    public String getElementUuid() { return elementUuid; }
    public String getFeature() { return feature; }
    public String getModel() { return model; }
    public List<Step> getSteps() { return steps; }
    public Outcome getOutcome() { return outcome; }
    public String getChosenSide() { return chosenSide; }
    public Double getConfidence() { return confidence; }
    public String getRationale() { return rationale; }
    public long getElapsedMillis() { return elapsedMillis; }

    /** One model turn within a run. */
    public static final class Step {
        private final int iteration;
        private final String assistantContent;
        private final List<ToolInvocation> toolInvocations = new ArrayList<>();

        Step(int iteration, String assistantContent) {
            this.iteration = iteration;
            this.assistantContent = assistantContent;
        }

        public void addToolInvocation(String tool, String arguments, String result) {
            toolInvocations.add(new ToolInvocation(tool, arguments, result));
        }

        public int getIteration() { return iteration; }
        public String getAssistantContent() { return assistantContent; }
        public List<ToolInvocation> getToolInvocations() { return toolInvocations; }
    }

    /** A single tool call the model made and the result it received. */
    public static final class ToolInvocation {
        private final String tool;
        private final String arguments;
        private final String result;

        ToolInvocation(String tool, String arguments, String result) {
            this.tool = tool;
            this.arguments = arguments;
            this.result = result;
        }

        public String getTool() { return tool; }
        public String getArguments() { return arguments; }
        public String getResult() { return result; }
    }
}
