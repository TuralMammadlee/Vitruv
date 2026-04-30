package tools.vitruv.framework.vsum.branch.data;

import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

/**
 * The fundamental nature of a conflict, independent of who made the change.
 *
 * <ul>
 *   <li>{@link #SYNTACTIC} — both sides modify a primitive attribute value
 *       on the same element/feature. The model shape is unchanged; only the
 *       stored value is ambiguous.</li>
 *   <li>{@link #SEMANTIC} — at least one side modifies a reference or
 *       containment link. The model topology is affected, so a naive
 *       last-writer-wins resolution can break consistency.</li>
 * </ul>
 *
 * <p>The classification is derived from {@link SemanticChangeType}. Severity calculation only needs a stable two-way
 * split between "data shape only" and "model meaning".
 */
public enum FundamentalConflictType {
    SYNTACTIC,
    SEMANTIC;

    /**
     * Classifies a single change type as syntactic (attribute) or semantic
     * (reference / containment / lifecycle / unknown).
     */
    public static FundamentalConflictType ofChangeType(SemanticChangeType type) {
        if (type == null) {
            return SEMANTIC;
        }
        return switch (type) {
            case ATTRIBUTE_SET,
                 ATTRIBUTE_CHANGED,
                 ATTRIBUTE_CLEARED,
                 ATTRIBUTE_VALUE_INSERTED,
                 ATTRIBUTE_VALUE_REMOVED -> SYNTACTIC;
            default -> SEMANTIC;
        };
    }

    /**
     * Combines the classification of both sides of a conflict. The result
     * is {@link #SEMANTIC} if either side is semantic, otherwise
     * {@link #SYNTACTIC}.
     */
    public static FundamentalConflictType combine(SemanticChangeType source, SemanticChangeType target) {
        FundamentalConflictType s = ofChangeType(source);
        FundamentalConflictType t = ofChangeType(target);
        return (s == SEMANTIC || t == SEMANTIC) ? SEMANTIC : SYNTACTIC;
    }
}
