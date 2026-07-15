package tools.vitruv.framework.vsum.branch.agentic;

/**
 * Sink for completed {@link AgenticTrace}s. Decouples the reasoning loop from
 * how (and whether) traces are persisted, so the advisor can run with no
 * persistence in tests and with file persistence in production.
 *
 * <p>Implementations must not throw: a failure to persist a trace must never
 * affect the merge outcome.
 */
@FunctionalInterface
public interface AgenticTraceSink {

    /** Accepts a finished trace for persistence or inspection. Must not throw. */
    void accept(AgenticTrace trace);

    /** Discards every trace. Used as the default when explainability output is not wired. */
    AgenticTraceSink NONE = trace -> { };
}
