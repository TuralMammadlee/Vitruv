package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.EObjectExistenceEChange;
import tools.vitruv.change.atomic.feature.FeatureEChange;
import tools.vitruv.change.atomic.root.RootEChange;
import tools.vitruv.change.composite.description.PropagatedChange;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.composite.description.VitruviusChange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Accumulates atomic {@code EChange<EObject>} instances between commits, grouped by the URI
 * of the resource they affect, and tags each with its {@link ChangeOrigin}.
 *
 * <p>Register an instance of this class as a {@link ChangePropagationListener} on the
 * {@link tools.vitruv.framework.vsum.branch.BranchAwareVirtualModel} to automatically collect
 * changes after each {@code propagateChange()} call. At commit time, call
 * {@link #drainAnnotatedChanges()} to retrieve and clear the buffer so the changes can be
 * serialized into the semantic changelog with proper origin tags.
 *
 * <p><b>Origin instrumentation:</b> The buffer uses identity comparison to distinguish
 * original (human-made) from consequential (engine-generated) changes. An {@code EChange}
 * that appears in any {@link PropagatedChange#getConsequentialChanges()} is tagged as
 * {@link ChangeOrigin#CONSEQUENTIAL}; all others are tagged as {@link ChangeOrigin#ORIGINAL}.
 *
 * <p>Thread-safety: all public methods and the listener callbacks are {@code synchronized} on
 * {@code this}.  {@link #finishedChangePropagation} is called from the VSUM/model thread while
 * {@link #drainAnnotatedChanges}, {@link #drainChanges}, {@link #hasChanges}, and {@link #size}
 * may be called from a background watcher thread (e.g. {@code VsumPostCommitWatcher}).
 */
public class SemanticChangeBuffer implements ChangePropagationListener {

    private static final Logger LOGGER = LogManager.getLogger(SemanticChangeBuffer.class);

    /**
     * An EChange paired with its detected origin.
     */
    public static class AnnotatedEChange {
        private final EChange<EObject> change;
        private final ChangeOrigin origin;

        public AnnotatedEChange(EChange<EObject> change, ChangeOrigin origin) {
            this.change = Objects.requireNonNull(change);
            this.origin = Objects.requireNonNull(origin);
        }

        public EChange<EObject> getChange() { return change; }
        public ChangeOrigin getOrigin() { return origin; }

        @Override
        public String toString() {
            return "AnnotatedEChange{" + change.getClass().getSimpleName() + ", " + origin + '}';
        }
    }

    /**
     * Accumulated changes, keyed by the string form of the resource URI.
     * LinkedHashMap preserves insertion order so replay is deterministic.
     */
    private final Map<String, List<AnnotatedEChange>> annotatedChangesByResource = new LinkedHashMap<>();

    /**
     * Total number of atomic changes accumulated since the last drain.
     */
    private int totalChanges = 0;

    @Override
    public synchronized void startedChangePropagation(VitruviusChange<Uuid> changeToPropagate) {
    }

    /**
     * Collects both original and consequential changes from every
     * {@link PropagatedChange}, tagging each with its detected {@link ChangeOrigin}.
     *
     * <p>Reaction changes are identified by checking whether their {@code EChange} instances
     * appear in the {@code consequentialChanges} of any other {@link PropagatedChange}.
     * Because the Vitruvius propagator reuses the same {@code EChange} object instances
     * for both the {@code consequentialChanges} composite and the subsequent
     * {@link PropagatedChange#getOriginalChange()}, an identity check is sufficient.
     */
    @Override
    public synchronized void finishedChangePropagation(Iterable<PropagatedChange> propagatedChanges) {
        List<PropagatedChange> pcList = new ArrayList<>();
        propagatedChanges.forEach(pcList::add);

        // Collect all EChange instances that are inside consequentialChanges of any PC.
        Set<Object> reactionEChanges = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PropagatedChange pc : pcList) {
            VitruviusChange<EObject> consequential = pc.getConsequentialChanges();
            if (consequential != null) {
                reactionEChanges.addAll(consequential.getEChanges());
            }
        }

        // Collect all changes, tagging each with its origin
        for (PropagatedChange pc : pcList) {
            // Original changes
            VitruviusChange<EObject> original = pc.getOriginalChange();
            if (original != null) {
                List<EChange<EObject>> eChanges = original.getEChanges();
                // Determine if this entire PC is a reaction output
                boolean isReaction = !eChanges.isEmpty() && reactionEChanges.containsAll(eChanges);
                ChangeOrigin origin = isReaction ? ChangeOrigin.CONSEQUENTIAL : ChangeOrigin.ORIGINAL;

                for (EChange<EObject> change : eChanges) {
                    String resourceUri = resolveResourceUri(change);
                    annotatedChangesByResource.computeIfAbsent(resourceUri, k -> new ArrayList<>())
                            .add(new AnnotatedEChange(change, origin));
                    totalChanges++;
                }

                if (isReaction) {
                    LOGGER.debug("Collected {} CONSEQUENTIAL change(s) from reaction PC", eChanges.size());
                }
            }

            // Consequential changes (engine-generated) — collect separately if not already collected
            VitruviusChange<EObject> consequential = pc.getConsequentialChanges();
            if (consequential != null) {
                for (EChange<EObject> change : consequential.getEChanges()) {
                    // Only add if this specific EChange was not already added via originalChange above
                    String resourceUri = resolveResourceUri(change);
                    List<AnnotatedEChange> existing = annotatedChangesByResource.get(resourceUri);
                    boolean alreadyCollected = existing != null && existing.stream()
                            .anyMatch(a -> a.getChange() == change);
                    if (!alreadyCollected) {
                        annotatedChangesByResource.computeIfAbsent(resourceUri, k -> new ArrayList<>())
                                .add(new AnnotatedEChange(change, ChangeOrigin.CONSEQUENTIAL));
                        totalChanges++;
                    }
                }
            }
        }
        LOGGER.debug("Buffer now holds {} atomic change(s) across {} resource(s)",
                totalChanges, annotatedChangesByResource.size());
    }

    /**
     * Returns an unmodifiable snapshot of the annotated changes and clears the buffer.
     * Each entry pairs an EChange with its detected {@link ChangeOrigin}.
     *
     * <p>Call this method once per commit, immediately before writing the changelog.
     *
     * @return immutable map of resource URI to ordered annotated changes.
     */
    public synchronized Map<String, List<AnnotatedEChange>> drainAnnotatedChanges() {
        Map<String, List<AnnotatedEChange>> snapshot = new LinkedHashMap<>();
        annotatedChangesByResource.forEach((uri, changes) ->
                snapshot.put(uri, Collections.unmodifiableList(new ArrayList<>(changes))));
        annotatedChangesByResource.clear();
        int drained = totalChanges;
        totalChanges = 0;
        LOGGER.info("Drained {} annotated change(s) from buffer for {} resource(s)", drained, snapshot.size());
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns an unmodifiable snapshot of the current buffer contents (without origin
     * annotation) and clears the buffer. This is the backward-compatible method.
     *
     * @return immutable map of resource URI to ordered atomic changes.
     */
    public synchronized Map<String, List<EChange<EObject>>> drainChanges() {
        Map<String, List<EChange<EObject>>> snapshot = new LinkedHashMap<>();
        annotatedChangesByResource.forEach((uri, annotated) -> {
            List<EChange<EObject>> plain = new ArrayList<>();
            for (AnnotatedEChange a : annotated) {
                plain.add(a.getChange());
            }
            snapshot.put(uri, Collections.unmodifiableList(plain));
        });
        annotatedChangesByResource.clear();
        int drained = totalChanges;
        totalChanges = 0;
        LOGGER.info("Drained {} atomic change(s) from buffer for {} resource(s)", drained, snapshot.size());
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns true if the buffer contains at least one change.
     */
    public synchronized boolean hasChanges() {
        return totalChanges > 0;
    }

    /**
     * Returns the total number of atomic changes currently in the buffer.
     */
    public synchronized int size() {
        return totalChanges;
    }

    /**
     * Determines the resource URI string for a given EChange.
     */
    private String resolveResourceUri(EChange<EObject> change) {
        if (change instanceof RootEChange<?> r) {
            String uri = r.getUri();
            return uri != null ? uri : "unknown-resource";
        }

        EObject element = null;
        if (change instanceof FeatureEChange<?, ?> f) {
            element = (EObject) f.getAffectedElement();
        } else if (change instanceof EObjectExistenceEChange<?> e) {
            element = (EObject) e.getAffectedElement();
        }

        if (element != null && element.eResource() != null) {
            URI uri = element.eResource().getURI();
            return uri != null ? uri.toString() : "unknown-resource";
        }

        LOGGER.debug("Cannot determine resource URI for change of type '{}', filing under 'unknown-resource'",
                change.getClass().getSimpleName());
        return "unknown-resource";
    }
}

