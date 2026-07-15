package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.vitruv.framework.vsum.branch.data.OwnerNotification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Delivers {@link OwnerNotification}s to the detected owner(s) of an escalated
 * deletion conflict (activity-diagram box {@code notify_owner}).
 *
 * <p>This repository is a headless / CLI tool with no email or webhook
 * transport, so "notification" is realized the same way as the audit trail: a
 * durable JSON artifact written under
 * {@code .vitruvius/notifications/<sourceBranch>-into-<targetBranch>-<elementUuid>.json}
 * plus a human-readable summary on the logger. An owner consumes the artifact
 * out of band and records a decision via
 * {@link OwnerDecisionStore} / {@code MergeManager.submitOwnerDecision}.
 */
public class OwnerNotifier {

    private static final Logger LOGGER = LogManager.getLogger(OwnerNotifier.class);

    private final Path repoRoot;
    private final Gson gson;

    /**
     * Creates a notifier rooted at the given Git repository.
     *
     * @param repoRoot the root directory of the Git repository.
     */
    public OwnerNotifier(Path repoRoot) {
        this.repoRoot = checkNotNull(repoRoot, "repoRoot must not be null");
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    /**
     * Returns whether a notification artifact already exists for this conflict.
     */
    public boolean notificationExists(OwnerNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        String filename = sanitize(notification.getSourceBranch()) + "-into-"
                + sanitize(notification.getTargetBranch()) + "-"
                + sanitize(notification.getDeletedElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("notifications").resolve(filename);
        return Files.exists(file);
    }

    /**
     * Writes the notification artifact to disk and logs a summary. The write
     * uses {@link StandardOpenOption#SYNC} so the notification is durably on
     * disk before this method returns.
     *
     * @param notification the notification payload to deliver.
     * @return the path of the written notification artifact.
     * @throws IOException if the artifact cannot be written.
     */
    public Path notify(OwnerNotification notification) throws IOException {
        Objects.requireNonNull(notification, "notification must not be null");

        String filename = sanitize(notification.getSourceBranch()) + "-into-"
                + sanitize(notification.getTargetBranch()) + "-"
                + sanitize(notification.getDeletedElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("notifications").resolve(filename);

        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                gson.toJson(notification),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.SYNC);

        LOGGER.info("Owner notification written: {} -> owner(s) {} | {}-severity, risk score {}, {} lost update(s)",
                file.getFileName(),
                notification.getDetectedOwners(),
                notification.getSeverity(),
                notification.getRiskScore(),
                notification.getLostUpdateCount());
        return file;
    }

    /**
     * Reads the {@code timestamp} field from an existing notification artifact.
     */
    public Optional<String> readNotificationTimestamp(OwnerNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        String filename = sanitize(notification.getSourceBranch()) + "-into-"
                + sanitize(notification.getTargetBranch()) + "-"
                + sanitize(notification.getDeletedElementUuid()) + ".json";
        Path file = repoRoot.resolve(".vitruvius").resolve("notifications").resolve(filename);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            OwnerNotification stored = gson.fromJson(Files.readString(file), OwnerNotification.class);
            return stored != null && stored.getTimestamp() != null
                    ? Optional.of(stored.getTimestamp()) : Optional.empty();
        } catch (IOException e) {
            LOGGER.debug("Could not read notification timestamp: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
