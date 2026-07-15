package tools.vitruv.framework.vsum.branch.data;

import java.util.Objects;

/**
 * The element-feature pair that a Reaction modified as a consequence of
 * replaying an original change.
 *
 * <p>The paper (section 4.5) defines the <em>footprint</em> of a change as
 * the set of {@code (element, feature)} pairs it modifies. For the
 * dependency-graph construction (section 4.8) only the <em>consequential</em>
 * footprint of each commit matters: the pairs written by Reactions when the
 * commit's original changes were applied.
 *
 * <p>A footprint is identified by the element's stable Vitruvius UUID (same
 * across branches for elements present at the branch point), the element's
 * EClass name, and the structural feature name. The UUID is the primary key
 * for cross-branch overlap detection; eClass and feature are carried along
 * for diagnostics and conflict reporting.
 *
 * <p>Instances are value objects: equality and hashing are based on all three
 * fields so that the same physical element-feature pair reported from two
 * different paths (e.g. recorded at commit time vs. computed from replay)
 * de-duplicates correctly in a set.
 *
 * @param elementUuid stable UUID of the element written by the Reaction.
 * @param eClass      EClass of the element in {@code "nsPrefix::Name"} format.
 * @param feature     name of the structural feature written by the Reaction.
 */
public record ConsequentialFootprint(String elementUuid, String eClass, String feature) {

    public ConsequentialFootprint {
        Objects.requireNonNull(elementUuid, "elementUuid must not be null");
        Objects.requireNonNull(feature,     "feature must not be null");
        // eClass may be null when the type cannot be determined at capture time
    }
}
