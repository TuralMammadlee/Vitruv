package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.Objects;

/**
 * Describes an update-vs-update conflict detected during a branch merge.
 *
 * <p>An update conflict occurs when both branches modify the <em>same</em>
 * structural feature on the <em>same</em> element (identified by UUID).
 * If the merge is accepted naively, one branch's value silently overwrites
 * the other's — a "lost update" scenario.
 *
 * <p>Unlike a {@link DeletionConflict}, neither branch deletes the element;
 * the model stays intact, but the final attribute/reference value is ambiguous.
 *
 * <p>Each conflict is tagged with an {@link OriginPermutation} (who edited each
 * side) and a {@link FundamentalConflictType} (attribute vs. reference/topology).
 * Both tags are computed in the constructor from the supplied entries and feed
 * into {@link #getSeverity()}.
 */
public class UpdateConflict {

    private final String elementUuid;
    private final String eClass;
    private final String featureName;
    private final String sourceBranch;
    private final String targetBranch;
    private final SemanticChangeEntry sourceEntry;
    private final SemanticChangeEntry targetEntry;
    private final OriginPermutation originPermutation;
    private final FundamentalConflictType fundamentalType;

    /**
     * Creates an update conflict. {@link OriginPermutation} and
     * {@link FundamentalConflictType} are derived from the supplied entries
     * so that callers cannot forget to set them.
     *
     * @param elementUuid  UUID of the element both branches modified.
     * @param eClass       EClass name of the element (for display).
     * @param featureName  name of the conflicting structural feature.
     * @param sourceBranch name of the source (incoming) branch.
     * @param targetBranch name of the target (current) branch.
     * @param sourceEntry  the change entry from the source branch.
     * @param targetEntry  the change entry from the target branch.
     */
    public UpdateConflict(String elementUuid, String eClass, String featureName,
                          String sourceBranch, String targetBranch,
                          SemanticChangeEntry sourceEntry, SemanticChangeEntry targetEntry) {
        this.elementUuid = Objects.requireNonNull(elementUuid);
        this.eClass = eClass;
        this.featureName = Objects.requireNonNull(featureName);
        this.sourceBranch = Objects.requireNonNull(sourceBranch);
        this.targetBranch = Objects.requireNonNull(targetBranch);
        this.sourceEntry = Objects.requireNonNull(sourceEntry);
        this.targetEntry = Objects.requireNonNull(targetEntry);
        this.originPermutation = OriginPermutation.of(sourceEntry.getOrigin(), targetEntry.getOrigin());
        this.fundamentalType = FundamentalConflictType.combine(
                sourceEntry.getChangeType(), targetEntry.getChangeType());
    }

    public String getElementUuid() { return elementUuid; }
    public String getEClass() { return eClass; }
    public String getFeatureName() { return featureName; }
    public String getSourceBranch() { return sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public SemanticChangeEntry getSourceEntry() { return sourceEntry; }
    public SemanticChangeEntry getTargetEntry() { return targetEntry; }

    /**
     * Returns the {@link OriginPermutation} of this conflict. Drives the
     * "original over consequential" auto-resolution rule.
     */
    public OriginPermutation getOriginPermutation() { return originPermutation; }

    /**
     * Returns the {@link FundamentalConflictType} of this conflict.
     * Semantic conflicts (reference/containment) are treated as higher risk
     * than syntactic (attribute) ones during severity calculation.
     */
    public FundamentalConflictType getFundamentalType() { return fundamentalType; }

    /**
     * Computes the severity from the permutation and fundamental type.
     *
     * <p>Rules (kept intentionally simple, in line with the in-scope wiring):
     * <ul>
     *   <li>{@link OriginPermutation#UNKNOWN_UNKNOWN}: {@link ConflictSeverity#MEDIUM}
     *       (conservative: we can't reason about origins).</li>
     *   <li>Mixed origin ({@link OriginPermutation#O_C} / {@link OriginPermutation#C_O}):
     *       {@link ConflictSeverity#MEDIUM} — auto-resolvable in favor of the human
     *       change, low residual risk.</li>
     *   <li>{@link OriginPermutation#C_C}: {@link ConflictSeverity#HIGH} — both
     *       sides are engine-generated, indicating a likely consistency-rule
     *       collision that needs review.</li>
     *   <li>{@link OriginPermutation#O_O}: {@link ConflictSeverity#HIGH} when the
     *       conflict is {@link FundamentalConflictType#SEMANTIC} (reference/topology
     *       impact), otherwise {@link ConflictSeverity#MEDIUM}.</li>
     * </ul>
     */
    public ConflictSeverity getSeverity() {
        if (originPermutation == OriginPermutation.UNKNOWN_UNKNOWN) {
            return ConflictSeverity.MEDIUM;
        }
        if (originPermutation.isMixedOrigin()) {
            return ConflictSeverity.MEDIUM;
        }
        if (originPermutation == OriginPermutation.C_C) {
            return ConflictSeverity.HIGH;
        }
        return fundamentalType == FundamentalConflictType.SEMANTIC
                ? ConflictSeverity.HIGH
                : ConflictSeverity.MEDIUM;
    }

    /**
     * Returns {@code true} if exactly one side is ORIGINAL and the other is
     * CONSEQUENTIAL. In this case, the ORIGINAL side should be preferred per
     * the Vitruvius rule. Delegates to {@link OriginPermutation#isMixedOrigin()}.
     */
    public boolean isOriginalVsConsequential() {
        return originPermutation.isMixedOrigin();
    }

    /**
     * Returns the entry that should be preferred when the permutation is
     * mixed-origin. Returns {@code null} otherwise (manual resolution required).
     */
    public SemanticChangeEntry getPreferredEntry() {
        return switch (originPermutation) {
            case O_C -> sourceEntry;
            case C_O -> targetEntry;
            default -> null;
        };
    }

    /**
     * Returns the branch name of the preferred entry, or {@code null} if
     * no auto-preference can be determined.
     */
    public String getPreferredBranch() {
        SemanticChangeEntry preferred = getPreferredEntry();
        if (preferred == null) return null;
        return preferred == sourceEntry ? sourceBranch : targetBranch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateConflict that = (UpdateConflict) o;
        return Objects.equals(elementUuid, that.elementUuid)
                && Objects.equals(featureName, that.featureName)
                && Objects.equals(sourceBranch, that.sourceBranch)
                && Objects.equals(targetBranch, that.targetBranch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elementUuid, featureName, sourceBranch, targetBranch);
    }

    @Override
    public String toString() {
        return "UpdateConflict{" +
                "element=" + eClass + " (uuid=" + elementUuid + ")" +
                ", feature='" + featureName + '\'' +
                ", source='" + sourceBranch + "' [" + sourceEntry.getOrigin() + "]" +
                ", target='" + targetBranch + "' [" + targetEntry.getOrigin() + "]" +
                ", severity=" + getSeverity() +
                '}';
    }
}
