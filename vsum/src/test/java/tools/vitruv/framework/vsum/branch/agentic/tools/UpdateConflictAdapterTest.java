package tools.vitruv.framework.vsum.branch.agentic.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolution;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolutionProvider;
import tools.vitruv.framework.vsum.branch.merge.MergeConflict;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link UpdateConflictAdapter} choice mapping between the
 * production conflict model and the replay engine's OURS/THEIRS model.
 */
class UpdateConflictAdapterTest {

    private static UpdateConflict focus() {
        SemanticChangeEntry e = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED).emfType("ReplaceSingleValued")
                .elementUuid("uuid-focus").eClass("entities::Entity").feature("name")
                .origin(ChangeOrigin.ORIGINAL).build();
        return new UpdateConflict("uuid-focus", "entities::Entity", "name", "s", "t", e, e);
    }

    private static MergeConflict mergeConflict(String uuid, String feature) {
        return new MergeConflict(uuid, MergeConflict.ConflictType.MODIFY_MODIFY,
                uuid, feature, "base", "ours", "theirs");
    }

    @Test
    @DisplayName("choosing source maps the focal conflict to THEIRS")
    void sourceMapsToTheirs() {
        ConflictResolutionProvider provider = UpdateConflictAdapter.providerFor(focus(), true);
        List<ConflictResolution> resolutions = provider.resolve(List.of(mergeConflict("uuid-focus", "name")));
        assertEquals(1, resolutions.size());
        assertEquals(ConflictResolution.Choice.THEIRS, resolutions.get(0).choice());
    }

    @Test
    @DisplayName("choosing target maps the focal conflict to OURS")
    void targetMapsToOurs() {
        ConflictResolutionProvider provider = UpdateConflictAdapter.providerFor(focus(), false);
        List<ConflictResolution> resolutions = provider.resolve(List.of(mergeConflict("uuid-focus", "name")));
        assertEquals(ConflictResolution.Choice.OURS, resolutions.get(0).choice());
    }

    @Test
    @DisplayName("non-focal conflicts always get the neutral OURS baseline")
    void nonFocalStaysOurs() {
        ConflictResolutionProvider provider = UpdateConflictAdapter.providerFor(focus(), true);
        List<ConflictResolution> resolutions = provider.resolve(List.of(
                mergeConflict("uuid-focus", "name"),
                mergeConflict("uuid-other", "name")));

        assertEquals(ConflictResolution.Choice.THEIRS, resolutions.get(0).choice());
        assertEquals(ConflictResolution.Choice.OURS, resolutions.get(1).choice());
    }
}
