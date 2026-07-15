package tools.vitruv.framework.vsum.branch.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.framework.vsum.branch.data.ConflictSeverity;
import tools.vitruv.framework.vsum.branch.data.DeletionConflict;
import tools.vitruv.framework.vsum.branch.data.OwnerNotification;
import tools.vitruv.framework.vsum.branch.data.SeverityThresholds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OwnerNotifierTest {

    @Test
    @DisplayName("writes notification artifact under .vitruvius/notifications/")
    void writesNotificationArtifact(@TempDir Path repoRoot) throws IOException {
        DeletionConflict conflict = escalatedConflict();
        OwnerNotification notification = OwnerNotification.forConflict(
                conflict, SeverityThresholds.defaults(), "feature", "main");

        OwnerNotifier notifier = new OwnerNotifier(repoRoot);
        Path written = notifier.notify(notification);

        assertTrue(written.startsWith(repoRoot.resolve(".vitruvius").resolve("notifications")));
        assertTrue(Files.exists(written));
        String json = Files.readString(written);
        assertTrue(json.contains("deleted-uuid"));
        assertTrue(json.contains("owner@example.com"));
        assertTrue(json.contains("\"riskScore\": 2"));
        assertTrue(json.contains("MEDIUM"));
    }

    @Test
    @DisplayName("OwnerNotification.forConflict captures affected elements and risk score")
    void notificationPayloadFields() {
        DeletionConflict conflict = escalatedConflict();
        OwnerNotification notification = OwnerNotification.forConflict(
                conflict, SeverityThresholds.defaults(), "feature", "main");

        assertEquals("deleted-uuid", notification.getDeletedElementUuid());
        assertEquals(Set.of("owner@example.com"), notification.getDetectedOwners());
        assertEquals(2, notification.getRiskScore());
        assertEquals(1, notification.getLostUpdateCount());
        assertEquals(ConflictSeverity.MEDIUM, notification.getSeverity());
        assertEquals(1, notification.getAffectedElements().size());
        assertEquals("child-uuid", notification.getAffectedElements().get(0).getElementUuid());
    }

    private static DeletionConflict escalatedConflict() {
        SemanticChangeEntry update = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid("child-uuid")
                .eClass("entities::Entity")
                .feature("name")
                .from("old")
                .to("new")
                .origin(ChangeOrigin.ORIGINAL)
                .build();
        return new DeletionConflict(
                "deleted-uuid", "entities::Entity",
                "feature", "main",
                List.of(update), true, ChangeOrigin.ORIGINAL,
                Set.of("owner@example.com"), true);
    }
}
