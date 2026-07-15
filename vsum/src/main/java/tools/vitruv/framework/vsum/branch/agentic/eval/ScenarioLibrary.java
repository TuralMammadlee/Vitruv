package tools.vitruv.framework.vsum.branch.agentic.eval;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.InMemoryResolutionHistory;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a small, self-contained set of labelled {@link EvalScenario}s plus the
 * seeded {@link InMemoryResolutionHistory} they assume, so both the statistical
 * baseline and the agentic advisor can be scored offline without a live Git
 * repository.
 *
 * <p>Two families of case are included:
 * <ul>
 *   <li><b>Mixed-origin</b> ({@code O_C}, {@code C_O}): the correct side is the
 *       ORIGINAL (human) one by the Vitruvius rule. These have <em>no</em> seeded
 *       history, so the history baseline abstains while a competent agent should
 *       still apply the origin rule — the interesting contrast.</li>
 *   <li><b>Same-origin with an established convention</b> ({@code O_O}): history is
 *       seeded so one side is strongly preferred; both advisors should follow it.</li>
 * </ul>
 */
public final class ScenarioLibrary {

    private static final String E_CLASS = "entities::Entity";

    private final InMemoryResolutionHistory history = new InMemoryResolutionHistory();
    private final List<EvalScenario> scenarios = new ArrayList<>();

    private ScenarioLibrary() {
        build();
    }

    /** Creates and populates a fresh library. */
    public static ScenarioLibrary create() {
        return new ScenarioLibrary();
    }

    /** The labelled scenarios. */
    public List<EvalScenario> scenarios() {
        return List.copyOf(scenarios);
    }

    /**
     * The resolution history the scenarios assume. Share this instance with both
     * the baseline {@code AuditHistoryAdvisor} and the agent's history tool so the
     * convention cases are decidable.
     */
    public InMemoryResolutionHistory history() {
        return history;
    }

    private void build() {
        // Mixed origin: ORIGINAL should beat CONSEQUENTIAL.
        scenarios.add(new EvalScenario("mixed-oc-attribute",
                attributeConflict("uuid-oc-attr", "name", ChangeOrigin.ORIGINAL, ChangeOrigin.CONSEQUENTIAL),
                "source", "O_C attribute change: human (source) beats engine (target)"));
        scenarios.add(new EvalScenario("mixed-co-attribute",
                attributeConflict("uuid-co-attr", "name", ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.ORIGINAL),
                "target", "C_O attribute change: human (target) beats engine (source)"));
        scenarios.add(new EvalScenario("mixed-oc-reference",
                referenceConflict("uuid-oc-ref", "type", ChangeOrigin.ORIGINAL, ChangeOrigin.CONSEQUENTIAL),
                "source", "O_C reference change: human (source) beats engine (target)"));
        scenarios.add(new EvalScenario("mixed-co-reference",
                referenceConflict("uuid-co-ref", "type", ChangeOrigin.CONSEQUENTIAL, ChangeOrigin.ORIGINAL),
                "target", "C_O reference change: human (target) beats engine (source)"));

        // Same origin with a seeded convention that favours the source side.
        UpdateConflict conventionSource = attributeConflict(
                "uuid-oo-conv-src", "label", ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);
        for (int i = 0; i < 5; i++) {
            history.recordSourceWin(conventionSource);
        }
        scenarios.add(new EvalScenario("convention-source",
                conventionSource, "source",
                "O_O attribute change: history strongly favours the source side (5-0)"));

        // Same origin with a seeded convention that favours the target side.
        UpdateConflict conventionTarget = attributeConflict(
                "uuid-oo-conv-tgt", "priority", ChangeOrigin.ORIGINAL, ChangeOrigin.ORIGINAL);
        for (int i = 0; i < 5; i++) {
            history.recordTargetWin(conventionTarget);
        }
        scenarios.add(new EvalScenario("convention-target",
                conventionTarget, "target",
                "O_O attribute change: history strongly favours the target side (0-5)"));
    }

    private UpdateConflict attributeConflict(String uuid, String feature,
                                             ChangeOrigin sourceOrigin, ChangeOrigin targetOrigin) {
        SemanticChangeEntry source = entry(uuid, feature, SemanticChangeType.ATTRIBUTE_CHANGED,
                sourceOrigin, "old", "sourceValue");
        SemanticChangeEntry target = entry(uuid, feature, SemanticChangeType.ATTRIBUTE_CHANGED,
                targetOrigin, "old", "targetValue");
        return new UpdateConflict(uuid, E_CLASS, feature, "feature-branch", "main", source, target);
    }

    private UpdateConflict referenceConflict(String uuid, String feature,
                                             ChangeOrigin sourceOrigin, ChangeOrigin targetOrigin) {
        SemanticChangeEntry source = entry(uuid, feature, SemanticChangeType.REFERENCE_CHANGED,
                sourceOrigin, "ref-old", "ref-source");
        SemanticChangeEntry target = entry(uuid, feature, SemanticChangeType.REFERENCE_CHANGED,
                targetOrigin, "ref-old", "ref-target");
        return new UpdateConflict(uuid, E_CLASS, feature, "feature-branch", "main", source, target);
    }

    private SemanticChangeEntry entry(String uuid, String feature, SemanticChangeType type,
                                      ChangeOrigin origin, String from, String to) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType("ReplaceSingleValued")
                .elementUuid(uuid)
                .eClass(E_CLASS)
                .feature(feature)
                .from(from)
                .to(to)
                .origin(origin)
                .build();
    }
}
