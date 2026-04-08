package tools.vitruv.framework.vsum.branch.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Configurable thresholds that map lost-update counts to {@link ConflictSeverity} levels.
 *
 * <p>Administrators can customize these boundaries so that the system adapts to different
 * project scales. A large enterprise project may set higher thresholds before a conflict
 * reaches CRITICAL, while a small research project may want stricter (lower) thresholds.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code mediumThreshold = 1}: 1+ lost updates → MEDIUM</li>
 *   <li>{@code highThreshold = 3}: 3+ lost updates → HIGH</li>
 *   <li>{@code criticalThreshold = 10}: 10+ lost updates → CRITICAL</li>
 * </ul>
 *
 * <p>Persisted as {@code .vitruvius/config/severity-thresholds.json}.
 *
 * @see ConflictSeverity#fromLostUpdateCount(int, SeverityThresholds)
 */
public class SeverityThresholds {

    private static final String CONFIG_FILE = "severity-thresholds.json";

    /** Minimum lost updates for MEDIUM severity. Default: 1. */
    private int mediumThreshold;

    /** Minimum lost updates for HIGH severity. Default: 3. */
    private int highThreshold;

    /** Minimum lost updates for CRITICAL severity. Default: 10. */
    private int criticalThreshold;

    /**
     * Creates thresholds with the given boundaries.
     *
     * @param mediumThreshold   minimum lost updates for MEDIUM.
     * @param highThreshold     minimum lost updates for HIGH.
     * @param criticalThreshold minimum lost updates for CRITICAL.
     * @throws IllegalArgumentException if thresholds are not strictly increasing.
     */
    public SeverityThresholds(int mediumThreshold, int highThreshold, int criticalThreshold) {
        if (mediumThreshold < 1) {
            throw new IllegalArgumentException("mediumThreshold must be >= 1, got " + mediumThreshold);
        }
        if (highThreshold <= mediumThreshold) {
            throw new IllegalArgumentException(
                    "highThreshold (" + highThreshold + ") must be > mediumThreshold (" + mediumThreshold + ")");
        }
        if (criticalThreshold <= highThreshold) {
            throw new IllegalArgumentException(
                    "criticalThreshold (" + criticalThreshold + ") must be > highThreshold (" + highThreshold + ")");
        }
        this.mediumThreshold = mediumThreshold;
        this.highThreshold = highThreshold;
        this.criticalThreshold = criticalThreshold;
    }

    /** No-arg constructor for Gson deserialization. Do not use directly. */
    private SeverityThresholds() {
        this(1, 3, 10);
    }

    /**
     * Returns the default thresholds (1/3/10).
     */
    public static SeverityThresholds defaults() {
        return new SeverityThresholds(1, 3, 10);
    }

    public int getMediumThreshold() { return mediumThreshold; }
    public int getHighThreshold() { return highThreshold; }
    public int getCriticalThreshold() { return criticalThreshold; }

    /**
     * Computes the severity for the given lost-update count.
     *
     * @param lostUpdateCount number of updates that would be destroyed.
     * @return the corresponding severity level.
     */
    public ConflictSeverity computeSeverity(int lostUpdateCount) {
        if (lostUpdateCount <= 0) return ConflictSeverity.LOW;
        if (lostUpdateCount < highThreshold) return ConflictSeverity.MEDIUM;
        if (lostUpdateCount < criticalThreshold) return ConflictSeverity.HIGH;
        return ConflictSeverity.CRITICAL;
    }

    /**
     * Loads thresholds from the config directory. Returns defaults if not found.
     *
     * @param configDir the {@code .vitruvius/config/} directory.
     * @return the loaded or default thresholds.
     */
    public static SeverityThresholds load(Path configDir) throws IOException {
        Path file = configDir.resolve(CONFIG_FILE);
        if (!Files.exists(file)) {
            return defaults();
        }
        String json = Files.readString(file);
        SeverityThresholds loaded = new Gson().fromJson(json, SeverityThresholds.class);
        // Validate after deserialization
        if (loaded.mediumThreshold < 1 || loaded.highThreshold <= loaded.mediumThreshold
                || loaded.criticalThreshold <= loaded.highThreshold) {
            throw new IOException("Invalid severity thresholds in " + file
                    + ": MEDIUM(" + loaded.mediumThreshold + ") < HIGH(" + loaded.highThreshold
                    + ") < CRITICAL(" + loaded.criticalThreshold + ") must hold");
        }
        return loaded;
    }

    /**
     * Saves thresholds to the config directory.
     *
     * @param configDir the {@code .vitruvius/config/} directory.
     */
    public void save(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve(CONFIG_FILE);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(file, gson.toJson(this));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SeverityThresholds that = (SeverityThresholds) o;
        return mediumThreshold == that.mediumThreshold
                && highThreshold == that.highThreshold
                && criticalThreshold == that.criticalThreshold;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mediumThreshold, highThreshold, criticalThreshold);
    }

    @Override
    public String toString() {
        return "SeverityThresholds{MEDIUM>=" + mediumThreshold
                + ", HIGH>=" + highThreshold
                + ", CRITICAL>=" + criticalThreshold + "}";
    }
}
