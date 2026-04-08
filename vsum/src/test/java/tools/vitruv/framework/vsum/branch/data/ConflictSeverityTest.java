package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConflictSeverity}.
 */
class ConflictSeverityTest {

    @Test
    @DisplayName("Severity levels are ordered LOW < MEDIUM < HIGH < CRITICAL")
    void levelsAreOrdered() {
        assertTrue(ConflictSeverity.LOW.getLevel() < ConflictSeverity.MEDIUM.getLevel());
        assertTrue(ConflictSeverity.MEDIUM.getLevel() < ConflictSeverity.HIGH.getLevel());
        assertTrue(ConflictSeverity.HIGH.getLevel() < ConflictSeverity.CRITICAL.getLevel());
    }

    @Test
    @DisplayName("isWithin returns true when severity is at or below the max")
    void isWithinAtOrBelow() {
        assertTrue(ConflictSeverity.LOW.isWithin(ConflictSeverity.LOW));
        assertTrue(ConflictSeverity.LOW.isWithin(ConflictSeverity.MEDIUM));
        assertTrue(ConflictSeverity.LOW.isWithin(ConflictSeverity.HIGH));
        assertTrue(ConflictSeverity.LOW.isWithin(ConflictSeverity.CRITICAL));

        assertTrue(ConflictSeverity.MEDIUM.isWithin(ConflictSeverity.MEDIUM));
        assertTrue(ConflictSeverity.MEDIUM.isWithin(ConflictSeverity.HIGH));
        assertTrue(ConflictSeverity.MEDIUM.isWithin(ConflictSeverity.CRITICAL));

        assertTrue(ConflictSeverity.HIGH.isWithin(ConflictSeverity.HIGH));
        assertTrue(ConflictSeverity.HIGH.isWithin(ConflictSeverity.CRITICAL));

        assertTrue(ConflictSeverity.CRITICAL.isWithin(ConflictSeverity.CRITICAL));
    }

    @Test
    @DisplayName("isWithin returns false when severity exceeds the max")
    void isWithinAbove() {
        assertFalse(ConflictSeverity.MEDIUM.isWithin(ConflictSeverity.LOW));
        assertFalse(ConflictSeverity.HIGH.isWithin(ConflictSeverity.MEDIUM));
        assertFalse(ConflictSeverity.CRITICAL.isWithin(ConflictSeverity.HIGH));
        assertFalse(ConflictSeverity.CRITICAL.isWithin(ConflictSeverity.LOW));
    }

    @Test
    @DisplayName("isWithin returns false for null maxAllowed")
    void isWithinNull() {
        assertFalse(ConflictSeverity.LOW.isWithin(null));
    }

    @Test
    @DisplayName("fromLostUpdateCount maps counts to correct severity")
    void fromLostUpdateCount() {
        assertEquals(ConflictSeverity.LOW, ConflictSeverity.fromLostUpdateCount(0));
        assertEquals(ConflictSeverity.LOW, ConflictSeverity.fromLostUpdateCount(-5));

        assertEquals(ConflictSeverity.MEDIUM, ConflictSeverity.fromLostUpdateCount(1));
        assertEquals(ConflictSeverity.MEDIUM, ConflictSeverity.fromLostUpdateCount(2));

        assertEquals(ConflictSeverity.HIGH, ConflictSeverity.fromLostUpdateCount(3));
        assertEquals(ConflictSeverity.HIGH, ConflictSeverity.fromLostUpdateCount(9));

        assertEquals(ConflictSeverity.CRITICAL, ConflictSeverity.fromLostUpdateCount(10));
        assertEquals(ConflictSeverity.CRITICAL, ConflictSeverity.fromLostUpdateCount(100));
    }

    @Test
    @DisplayName("All severities have descriptions")
    void descriptionsNotEmpty() {
        for (ConflictSeverity severity : ConflictSeverity.values()) {
            assertNotNull(severity.getDescription());
            assertFalse(severity.getDescription().isBlank());
        }
    }
}
