package tools.vitruv.framework.vsum.branch;

import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;

import java.util.Optional;

/**
 * Strategy interface for domain-specific conflict resolution hints.
 *
 * <p>A {@code DomainValidator} is consulted by {@link MergeManager} for
 * update conflicts that the origin rule cannot resolve automatically — that
 * is, when both sides have the same {@link tools.vitruv.framework.vsum.branch.storage.ChangeOrigin}
 * or the origin is unknown. If the validator returns a non-empty result,
 * that entry is applied without involving the UI. If it returns empty, the
 * conflict falls through to manual resolution.
 *
 * <p>Implement this interface to encode domain knowledge about which branch
 * should win for specific element types, feature combinations, or contexts.
 * Implementations must be pure: they must not modify any state and must not
 * throw checked exceptions.
 *
 * <p>Example:
 * <pre>
 * DomainValidator schemaValidator = conflict -> {
 *     if ("entities::Schema".equals(conflict.getEClass())
 *             && "version".equals(conflict.getFeatureName())) {
 *         // Domain rule: schema version from the source branch always wins
 *         return Optional.of(conflict.getSourceEntry());
 *     }
 *     return Optional.empty();
 * };
 * mergeManager.setDomainValidator(schemaValidator);
 * </pre>
 */
@FunctionalInterface
public interface DomainValidator {

    /**
     * Suggests the preferred resolution entry for the given conflict, or
     * empty if no domain-specific default applies.
     *
     * @param conflict the update conflict to evaluate, never null.
     * @return the preferred {@link SemanticChangeEntry}, or empty to fall
     *         through to manual resolution.
     */
    Optional<SemanticChangeEntry> suggestResolution(UpdateConflict conflict);

    /**
     * No-op validator: never suggests a resolution.
     * All non-mixed-origin conflicts fall through to manual resolution.
     * This is the default used by {@link MergeManager} when no custom
     * validator has been installed.
     */
    DomainValidator NONE = conflict -> Optional.empty();
}
