package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChangeOrigin}.
 */
class ChangeOriginTest {

    @Test
    @DisplayName("ORIGINAL has highest priority")
    void originalHasHighestPriority() {
        assertTrue(ChangeOrigin.ORIGINAL.getPriority() > ChangeOrigin.CONSEQUENTIAL.getPriority());
        assertTrue(ChangeOrigin.ORIGINAL.getPriority() > ChangeOrigin.UNKNOWN.getPriority());
    }

    @Test
    @DisplayName("CONSEQUENTIAL has higher priority than UNKNOWN")
    void consequentialHigherThanUnknown() {
        assertTrue(ChangeOrigin.CONSEQUENTIAL.getPriority() > ChangeOrigin.UNKNOWN.getPriority());
    }

    @Test
    @DisplayName("All values have non-null descriptions")
    void allValuesHaveDescriptions() {
        for (ChangeOrigin origin : ChangeOrigin.values()) {
            assertNotNull(origin.getDescription());
            assertFalse(origin.getDescription().isBlank());
        }
    }

    @Test
    @DisplayName("SemanticChangeEntry defaults to UNKNOWN origin when not set")
    void entryDefaultsToUnknownOrigin() {
        var entry = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ELEMENT_CREATED)
                .emfType("CreateEObject")
                .build();
        assertEquals(ChangeOrigin.UNKNOWN, entry.getOrigin());
    }

    @Test
    @DisplayName("SemanticChangeEntry stores explicitly set origin")
    void entryStoresExplicitOrigin() {
        var entry = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .origin(ChangeOrigin.ORIGINAL)
                .build();
        assertEquals(ChangeOrigin.ORIGINAL, entry.getOrigin());
    }

    @Test
    @DisplayName("Origin is included in equals/hashCode")
    void originIncludedInEquality() {
        var entry1 = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("Test").origin(ChangeOrigin.ORIGINAL).build();
        var entry2 = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("Test").origin(ChangeOrigin.CONSEQUENTIAL).build();

        assertNotEquals(entry1, entry2, "Entries with different origins must not be equal");
    }

    @Test
    @DisplayName("Origin is included in toString")
    void originIncludedInToString() {
        var entry = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ELEMENT_CREATED)
                .emfType("CreateEObject").origin(ChangeOrigin.ORIGINAL).build();
        assertTrue(entry.toString().contains("ORIGINAL"));
    }
}
