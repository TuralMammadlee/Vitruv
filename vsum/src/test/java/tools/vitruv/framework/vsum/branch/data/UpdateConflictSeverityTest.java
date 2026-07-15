package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link UpdateConflict#getSeverity()} spans all four
 * {@link ConflictSeverity} levels based on origin permutation and fundamental
 * conflict type.
 */
class UpdateConflictSeverityTest {

    private static SemanticChangeEntry entry(SemanticChangeType type, ChangeOrigin origin) {
        return SemanticChangeEntry.builder()
                .index(0)
                .changeType(type)
                .emfType(type.name())
                .elementUuid("uuid-1")
                .eClass("entities::Entity")
                .feature("f")
                .origin(origin)
                .build();
    }

    private static UpdateConflict conflict(SemanticChangeType s, ChangeOrigin so,
                                           SemanticChangeType t, ChangeOrigin to) {
        SemanticChangeEntry src = entry(s, so);
        SemanticChangeEntry tgt = entry(t, to);
        return new UpdateConflict("uuid-1", "entities::Entity", "f",
                "source", "target", src, tgt);
    }

    private static final SemanticChangeType ATTR = SemanticChangeType.ATTRIBUTE_CHANGED;
    private static final SemanticChangeType REF = SemanticChangeType.REFERENCE_CHANGED;

    @Test
    @DisplayName("mixed origin + syntactic = LOW")
    void mixedSyntacticIsLow() {
        assertEquals(ConflictSeverity.LOW,
                conflict(ATTR, ChangeOrigin.ORIGINAL, ATTR, ChangeOrigin.CONSEQUENTIAL).getSeverity());
    }

    @Test
    @DisplayName("mixed origin + semantic = MEDIUM")
    void mixedSemanticIsMedium() {
        assertEquals(ConflictSeverity.MEDIUM,
                conflict(REF, ChangeOrigin.ORIGINAL, ATTR, ChangeOrigin.CONSEQUENTIAL).getSeverity());
    }

    @Test
    @DisplayName("unknown origin = MEDIUM")
    void unknownIsMedium() {
        assertEquals(ConflictSeverity.MEDIUM,
                conflict(ATTR, ChangeOrigin.UNKNOWN, ATTR, ChangeOrigin.UNKNOWN).getSeverity());
    }

    @Test
    @DisplayName("O_O + syntactic = MEDIUM")
    void originalSyntacticIsMedium() {
        assertEquals(ConflictSeverity.MEDIUM,
                conflict(ATTR, ChangeOrigin.ORIGINAL, ATTR, ChangeOrigin.ORIGINAL).getSeverity());
    }

    @Test
    @DisplayName("O_O + semantic = HIGH")
    void originalSemanticIsHigh() {
        assertEquals(ConflictSeverity.HIGH,
                conflict(REF, ChangeOrigin.ORIGINAL, REF, ChangeOrigin.ORIGINAL).getSeverity());
    }

    @Test
    @DisplayName("C_C + syntactic = HIGH")
    void consequentialSyntacticIsHigh() {
        assertEquals(ConflictSeverity.HIGH,
                conflict(ATTR, ChangeOrigin.CONSEQUENTIAL, ATTR, ChangeOrigin.CONSEQUENTIAL).getSeverity());
    }

    @Test
    @DisplayName("C_C + semantic = CRITICAL")
    void consequentialSemanticIsCritical() {
        assertEquals(ConflictSeverity.CRITICAL,
                conflict(REF, ChangeOrigin.CONSEQUENTIAL, REF, ChangeOrigin.CONSEQUENTIAL).getSeverity());
    }
}
