package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.AuditLogEntry;
import tools.vitruv.framework.vsum.branch.data.AutoResolutionOutcome;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.DeletionPolicy;
import tools.vitruv.framework.vsum.branch.data.UpdateConflict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AuditLogger} and {@link AuditLogEntry}.
 *
 * <p>Covers:
 * <ul>
 *   <li>file naming convention (sanitization of branch names)</li>
 *   <li>JSON structure and field round-trip</li>
 *   <li>synchronous flush semantics (file exists immediately after flush returns)</li>
 *   <li>both deletion and update auto-resolved entry shapes</li>
 *   <li>empty-buffer flush behaviour</li>
 * </ul>
 */
class AuditLoggerTest {

    @Nested
    @DisplayName("file naming and creation")
    class FileNaming {

        @Test
        @DisplayName("audit file lives under .vitruvius/audit/")
        void auditFileLocation(@TempDir Path repoRoot) {
            AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");

            assertTrue(logger.getAuditFile().startsWith(repoRoot.resolve(".vitruvius").resolve("audit")),
                    "audit file must live under .vitruvius/audit/");
            assertTrue(logger.getAuditFile().getFileName().toString().endsWith(".audit.json"));
        }

        @Test
        @DisplayName("branch names with unsafe characters are sanitised")
        void branchNameSanitisation(@TempDir Path repoRoot) {
            AuditLogger logger = new AuditLogger(repoRoot, "feature/x", "main:y");

            String filename = logger.getAuditFile().getFileName().toString();
            assertFalse(filename.contains("/"), "forward slash must be replaced");
            assertFalse(filename.contains(":"), "colon must be replaced (Windows-incompatible)");
            assertTrue(filename.startsWith("feature_x-into-main_y-"),
                    "sanitised pattern, was: " + filename);
        }

        @Test
        @DisplayName("constructor rejects null arguments")
        void constructorRejectsNull(@TempDir Path repoRoot) {
            assertThrows(NullPointerException.class, () -> new AuditLogger(null, "a", "b"));
            assertThrows(NullPointerException.class, () -> new AuditLogger(repoRoot, null, "b"));
            assertThrows(NullPointerException.class, () -> new AuditLogger(repoRoot, "a", null));
        }
    }

    @Nested
    @DisplayName("flush() synchronous on-disk semantics")
    class Flush {

        @Test
        @DisplayName("flush with no entries writes an empty JSON array")
        void emptyFlush(@TempDir Path repoRoot) throws IOException {
            AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");

            assertTrue(logger.isEmpty());
            assertEquals(0, logger.size());

            Path written = logger.flush();
            assertTrue(Files.exists(written), "audit file must be on disk immediately after flush returns");
            assertEquals("[]", Files.readString(written).trim());
        }

        @Test
        @DisplayName("flush creates parent directories on demand")
        void flushCreatesParents(@TempDir Path repoRoot) throws IOException {
            // .vitruvius/audit does not exist yet
            assertFalse(Files.exists(repoRoot.resolve(".vitruvius").resolve("audit")));

            AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");
            logger.flush();

            assertTrue(Files.exists(repoRoot.resolve(".vitruvius").resolve("audit")));
        }

        @Test
        @DisplayName("flush is idempotent — calling twice overwrites with current contents")
        void flushIsIdempotent(@TempDir Path repoRoot) throws IOException {
            AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");
            logger.log(deletionEntry("uuid-1", "RESTRICT_DELETIONS"));

            Path firstWrite = logger.flush();
            String firstContents = Files.readString(firstWrite);

            // Adding another entry and flushing again writes both entries (truncates previous file)
            logger.log(deletionEntry("uuid-2", "TOMBSTONE_WITH_WARNING"));
            Path secondWrite = logger.flush();
            String secondContents = Files.readString(secondWrite);

            assertEquals(firstWrite, secondWrite, "same file path across flushes");
            assertTrue(firstContents.contains("uuid-1"));
            assertTrue(secondContents.contains("uuid-1"));
            assertTrue(secondContents.contains("uuid-2"));
        }
    }

    @Nested
    @DisplayName("AuditLogEntry serialisation")
    class EntrySerialisation {

        @Test
        @DisplayName("deletion entry round-trip: every field survives JSON serialisation")
        void deletionEntryRoundTrip(@TempDir Path repoRoot) throws IOException {
            AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");
            logger.log(deletionEntry("element-uuid-42", "RECOVER_FROM_ANCESTOR"));

            String json = Files.readString(logger.flush());

            assertAll("all entry fields present in JSON",
                    () -> assertTrue(json.contains("\"timestamp\""), "timestamp"),
                    () -> assertTrue(json.contains("\"DELETION\""), "conflictType"),
                    () -> assertTrue(json.contains("element-uuid-42"), "elementUuid"),
                    () -> assertTrue(json.contains("RECOVER_FROM_ANCESTOR"), "resolution"),
                    () -> assertTrue(json.contains("\"reason\""), "reason"),
                    () -> assertTrue(json.contains("test rationale"), "rationale"),
                    () -> assertTrue(json.contains("\"sourceBranch\": \"feature\""), "sourceBranch"),
                    () -> assertTrue(json.contains("\"targetBranch\": \"main\""), "targetBranch"));
        }

        @Test
        @DisplayName("update auto-resolved entry uses null rationale (header includes serializeNulls)")
        void updateEntryNullRationale(@TempDir Path repoRoot) throws IOException {
            AuditLogger logger = new AuditLogger(repoRoot, "feature", "main");

            UpdateConflict conflict = new UpdateConflict(
                    "element-77", "entities::Entity", "name",
                    "feature", "main",
                    SemanticChangeEntryFactory.attributeChange("element-77", "name", ChangeOrigin.ORIGINAL),
                    SemanticChangeEntryFactory.attributeChange("element-77", "name", ChangeOrigin.CONSEQUENTIAL));
            AutoResolutionOutcome.ResolvedConflict resolved = new AutoResolutionOutcome.ResolvedConflict(
                    conflict, conflict.getSourceEntry(), "origin rule: ORIGINAL over CONSEQUENTIAL");

            logger.log(AuditLogEntry.forUpdateAutoResolved(resolved, "feature", "main"));
            String json = Files.readString(logger.flush());

            assertTrue(json.contains("UPDATE_AUTO_RESOLVED"));
            assertTrue(json.contains("element-77"));
            assertTrue(json.contains("origin rule"));
            assertTrue(json.contains("\"rationale\": null"),
                    "auto-resolved entries should serialise rationale as null, not omit it");
        }

        @Test
        @DisplayName("entry constructor rejects null required fields")
        void entryConstructorValidation() {
            AutoResolutionOutcome.ResolvedConflict nullResolved = null;
            assertThrows(NullPointerException.class,
                    () -> AuditLogEntry.forUpdateAutoResolved(nullResolved, "a", "b"));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static AuditLogEntry deletionEntry(String elementUuid, String policy) {
        DeletionConflict conflict = new DeletionConflict(
                elementUuid, "entities::Entity",
                "feature", "main",
                List.of(SemanticChangeEntryFactory.attributeChange(elementUuid, "name", ChangeOrigin.ORIGINAL)),
                true, ChangeOrigin.ORIGINAL);
        DeletionConflictResolver.Resolution resolution = new DeletionConflictResolver.Resolution(
                conflict, DeletionPolicy.valueOf(policy), "test reason", "test rationale");
        return AuditLogEntry.forDeletion(resolution, "feature", "main");
    }

    /** Small helper for building canonical SemanticChangeEntry instances used by these tests. */
    private static final class SemanticChangeEntryFactory {
        static SemanticChangeEntry attributeChange(String elementUuid, String feature, ChangeOrigin origin) {
            return SemanticChangeEntry.builder()
                    .index(0)
                    .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                    .emfType("ReplaceSingleValuedEAttribute")
                    .elementUuid(elementUuid)
                    .eClass("entities::Entity")
                    .feature(feature)
                    .origin(origin)
                    .build();
        }
    }
}
