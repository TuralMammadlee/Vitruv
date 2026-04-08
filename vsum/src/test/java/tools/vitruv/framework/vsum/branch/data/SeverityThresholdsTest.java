package tools.vitruv.framework.vsum.branch.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SeverityThresholds}.
 */
class SeverityThresholdsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Default thresholds are 1/3/10")
    void defaults() {
        SeverityThresholds t = SeverityThresholds.defaults();
        assertEquals(1, t.getMediumThreshold());
        assertEquals(3, t.getHighThreshold());
        assertEquals(10, t.getCriticalThreshold());
    }

    @Test
    @DisplayName("computeSeverity with default thresholds")
    void computeWithDefaults() {
        SeverityThresholds t = SeverityThresholds.defaults();
        assertEquals(ConflictSeverity.LOW, t.computeSeverity(0));
        assertEquals(ConflictSeverity.LOW, t.computeSeverity(-5));
        assertEquals(ConflictSeverity.MEDIUM, t.computeSeverity(1));
        assertEquals(ConflictSeverity.MEDIUM, t.computeSeverity(2));
        assertEquals(ConflictSeverity.HIGH, t.computeSeverity(3));
        assertEquals(ConflictSeverity.HIGH, t.computeSeverity(9));
        assertEquals(ConflictSeverity.CRITICAL, t.computeSeverity(10));
        assertEquals(ConflictSeverity.CRITICAL, t.computeSeverity(100));
    }

    @Test
    @DisplayName("Custom thresholds change severity boundaries")
    void customThresholds() {
        SeverityThresholds t = new SeverityThresholds(5, 20, 50);
        assertEquals(ConflictSeverity.LOW, t.computeSeverity(0));
        assertEquals(ConflictSeverity.MEDIUM, t.computeSeverity(5));
        assertEquals(ConflictSeverity.MEDIUM, t.computeSeverity(19));
        assertEquals(ConflictSeverity.HIGH, t.computeSeverity(20));
        assertEquals(ConflictSeverity.HIGH, t.computeSeverity(49));
        assertEquals(ConflictSeverity.CRITICAL, t.computeSeverity(50));
    }

    @Test
    @DisplayName("mediumThreshold < 1 throws")
    void mediumTooLow() {
        assertThrows(IllegalArgumentException.class, () -> new SeverityThresholds(0, 3, 10));
    }

    @Test
    @DisplayName("highThreshold <= mediumThreshold throws")
    void highNotGreater() {
        assertThrows(IllegalArgumentException.class, () -> new SeverityThresholds(3, 3, 10));
        assertThrows(IllegalArgumentException.class, () -> new SeverityThresholds(3, 2, 10));
    }

    @Test
    @DisplayName("criticalThreshold <= highThreshold throws")
    void criticalNotGreater() {
        assertThrows(IllegalArgumentException.class, () -> new SeverityThresholds(1, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new SeverityThresholds(1, 3, 2));
    }

    @Test
    @DisplayName("Save and load round-trip")
    void saveAndLoad() throws IOException {
        SeverityThresholds original = new SeverityThresholds(2, 5, 15);
        original.save(tempDir);

        SeverityThresholds loaded = SeverityThresholds.load(tempDir);
        assertEquals(original, loaded);
        assertEquals(2, loaded.getMediumThreshold());
        assertEquals(5, loaded.getHighThreshold());
        assertEquals(15, loaded.getCriticalThreshold());
    }

    @Test
    @DisplayName("Load from nonexistent file returns defaults")
    void loadDefaultsWhenMissing() throws IOException {
        SeverityThresholds loaded = SeverityThresholds.load(tempDir);
        assertEquals(SeverityThresholds.defaults(), loaded);
    }

    @Test
    @DisplayName("fromLostUpdateCount delegates to configurable thresholds")
    void fromLostUpdateCountWithCustom() {
        SeverityThresholds custom = new SeverityThresholds(5, 20, 50);
        // 4 would be HIGH with defaults, but MEDIUM with custom
        assertEquals(ConflictSeverity.MEDIUM, ConflictSeverity.fromLostUpdateCount(4, custom));
        // 3 would be HIGH with defaults, but MEDIUM with custom
        assertEquals(ConflictSeverity.MEDIUM, ConflictSeverity.fromLostUpdateCount(3, custom));
        // 20 would be CRITICAL with defaults, but HIGH with custom
        assertEquals(ConflictSeverity.HIGH, ConflictSeverity.fromLostUpdateCount(20, custom));
    }

    @Test
    @DisplayName("fromLostUpdateCount without thresholds uses defaults")
    void fromLostUpdateCountDefaultBackward() {
        // Same as the old hardcoded behavior
        assertEquals(ConflictSeverity.LOW, ConflictSeverity.fromLostUpdateCount(0));
        assertEquals(ConflictSeverity.MEDIUM, ConflictSeverity.fromLostUpdateCount(1));
        assertEquals(ConflictSeverity.HIGH, ConflictSeverity.fromLostUpdateCount(3));
        assertEquals(ConflictSeverity.CRITICAL, ConflictSeverity.fromLostUpdateCount(10));
    }

    @Test
    @DisplayName("Equality and hashCode")
    void equality() {
        SeverityThresholds a = new SeverityThresholds(1, 3, 10);
        SeverityThresholds b = new SeverityThresholds(1, 3, 10);
        SeverityThresholds c = new SeverityThresholds(2, 5, 15);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
