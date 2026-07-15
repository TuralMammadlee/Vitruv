package tools.vitruv.framework.vsum.branch.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.uuid.UuidResolver;
import tools.vitruv.change.changederivation.persistence.DeltaPersistence;
import tools.vitruv.framework.vsum.branch.data.ConsequentialFootprint;
import tools.vitruv.framework.vsum.branch.data.FileOperation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Writes and reads semantic changelog files stored under
 * {@code .vitruvius/changelogs/<branch>/json/<shortSha>.json} (JSON) and
 * {@code .vitruvius/changelogs/<branch>/xmi/<shortSha>.xmi} (XMI delta
 * snapshots).
 *
 * <p>
 * At commit time, call {@link #write} with the drained contents of a
 * {@link SemanticChangeBuffer} to produce both files atomically.
 * Both files are staged as part of the same Git commit.
 *
 * <p>
 * <b>JSON format</b> - human-readable, operation-based, UUID-keyed (stable
 * across branches):
 * 
 * <pre>
 * {
 *   "formatVersion": "1.0",
 *   "commit": { "sha": "...", "branch": "...", "author": { ... }, "message": "...", "parentShas": [...] },
 *   "fileChanges": [
 *     {
 *       "operation": "MODIFIED",
 *       "path": "model/example.xmi",
 *       "oldPath": null,
 *       "semanticChanges": [ { "index": 0, "changeType": "ELEMENT_CREATED", ... }, ... ]
 *     }
 *   ],
 *   "summary": { "totalFileChanges": 1, "totalSemanticChanges": 3, "affectedElementUuids": [...] }
 * }
 * </pre>
 *
 * <p>
 * <b>XMI format</b> - machine-readable, HierarchicalId-keyed, suitable for
 * replay and three-way merge.
 * One XMI file is written per changed resource using
 * {@link DeltaPersistence#saveResourceAsChanges} (state-based snapshot of the
 * current model state
 * expressed as creation EChanges).
 */
public class SemanticChangelogManager {

    private static final Logger LOGGER = LogManager.getLogger(SemanticChangelogManager.class);
    private static final String FORMAT_VERSION = "1.1";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Path repositoryRoot;
    private final Gson gson;

    /**
     * Creates a manager for the Git repository at the given root.
     *
     * @param repositoryRoot root directory of the repository, must not be null.
     */
    public SemanticChangelogManager(Path repositoryRoot) {
        this.repositoryRoot = checkNotNull(repositoryRoot, "repositoryRoot must not be null");
        this.gson = buildGson();
    }

    /**
     * Writes the JSON changelog and XMI delta snapshots for the given commit.
     *
     * <p>
     * JSON is written to
     * {@code .vitruvius/changelogs/<branch>/json/<shortSha>.json}.
     * XMI snapshots are written to
     * {@code .vitruvius/changelogs/<branch>/xmi/<shortSha>.xmi}
     * for each resource that had changes.
     *
     * @param commitSha         full 40-character commit SHA.
     * @param branch            branch name.
     * @param author            author name.
     * @param authorDate        date the changes were authored.
     * @param message           commit message.
     * @param parentShas        parent commit SHAs (one for normal commits, two for
     *                          merge commits).
     * @param changesByResource map of resource URI -> ordered atomic EChanges, from
     *                          {@link SemanticChangeBuffer#drainChanges()}.
     * @param activeResources   all currently loaded EMF Resources; used to locate
     *                          the resource objects for XMI snapshot writing.
     *                          May be null or empty to skip XMI.
     * @param uuidResolver      resolver used to convert EObjects to stable UUIDs
     *                          for JSON output.
     * @return list of paths of all written files (JSON + any XMI files) for Git
     *         staging.
     * @throws IOException if the JSON file cannot be written (XMI failures are
     *                     non-fatal).
     */
    public List<Path> write(String commitSha, String branch, String author, LocalDateTime authorDate, String message,
            List<String> parentShas, Map<String, List<EChange<EObject>>> changesByResource,
            Collection<Resource> activeResources, UuidResolver uuidResolver) throws IOException {

        checkNotNull(commitSha, "commitSha must not be null");
        checkNotNull(branch, "branch must not be null");
        checkNotNull(changesByResource, "changesByResource must not be null");
        checkNotNull(uuidResolver, "uuidResolver must not be null");

        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        List<Path> writtenFiles = new ArrayList<>();

        // Write JSON changelog
        EChangeToEntryConverter converter = new EChangeToEntryConverter(uuidResolver);
        ChangelogDocument document = buildDocument(commitSha, branch, author, authorDate, message, parentShas,
                changesByResource, converter);

        Path jsonDir = repositoryRoot.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
        Files.createDirectories(jsonDir);
        Path jsonFile = jsonDir.resolve(shortSha + ".json");
        Files.writeString(jsonFile, gson.toJson(document));
        writtenFiles.add(jsonFile);
        LOGGER.info("JSON changelog written: {} ({} file change(s), {} semantic change(s))", jsonFile.getFileName(),
                document.fileChanges.size(), document.summary.totalSemanticChanges);

        // Write XMI delta snapshots
        writtenFiles.addAll(writeXmiSnapshots(shortSha, branch, changesByResource.keySet(), activeResources));
        return writtenFiles;
    }

    /**
     * Writes the JSON changelog and XMI delta snapshots for the given commit,
     * fully utilizing the origin tags associated with each change.
     *
     * @see #write(String, String, String, LocalDateTime, String, List, Map, Collection, UuidResolver)
     */
    public List<Path> writeAnnotated(String commitSha, String branch, String author, LocalDateTime authorDate, String message,
            List<String> parentShas, Map<String, List<SemanticChangeBuffer.AnnotatedEChange>> annotatedChangesByResource,
            Collection<Resource> activeResources, UuidResolver uuidResolver) throws IOException {

        checkNotNull(commitSha, "commitSha must not be null");
        checkNotNull(branch, "branch must not be null");
        checkNotNull(annotatedChangesByResource, "annotatedChangesByResource must not be null");
        checkNotNull(uuidResolver, "uuidResolver must not be null");

        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        List<Path> writtenFiles = new ArrayList<>();

        // Write JSON changelog
        EChangeToEntryConverter converter = new EChangeToEntryConverter(uuidResolver);
        ChangelogDocument document = buildDocumentAnnotated(commitSha, branch, author, authorDate, message, parentShas,
                annotatedChangesByResource, converter);

        Path jsonDir = repositoryRoot.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
        Files.createDirectories(jsonDir);
        Path jsonFile = jsonDir.resolve(shortSha + ".json");
        Files.writeString(jsonFile, gson.toJson(document));
        writtenFiles.add(jsonFile);
        LOGGER.info("Annotated JSON changelog written: {} ({} file change(s), {} semantic change(s))", jsonFile.getFileName(),
                document.fileChanges.size(), document.summary.totalSemanticChanges);

        // Write XMI delta snapshots
        writtenFiles.addAll(writeXmiSnapshots(shortSha, branch, annotatedChangesByResource.keySet(), activeResources));
        return writtenFiles;
    }

    private List<Path> writeXmiSnapshots(String shortSha, String branch, Collection<String> resourceUris, Collection<Resource> activeResources) throws IOException {
        List<Path> writtenFiles = new ArrayList<>();

        if (activeResources != null && !activeResources.isEmpty()) {
            Path xmiDir = repositoryRoot.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("xmi");
            Files.createDirectories(xmiDir);

            for (String resourceUri : resourceUris) {
                Resource resource = findResource(activeResources, resourceUri);
                if (resource == null) {
                    LOGGER.debug("No loaded resource found for URI '{}', skipping XMI snapshot", resourceUri);
                    continue;
                }
                String resourceName = deriveResourceName(resourceUri);
                Path xmiFile = xmiDir.resolve(shortSha + "-" + resourceName + ".xmi");
                try {
                    DeltaPersistence.saveResourceAsChanges(resource, xmiFile);
                    writtenFiles.add(xmiFile);
                    LOGGER.debug("XMI delta snapshot written: {}", xmiFile.getFileName());
                } catch (Exception e) {
                    LOGGER.warn("Failed to write XMI snapshot for '{}' (non-critical): {}", resourceName,
                            e.getMessage());
                }
            }
        }
        return writtenFiles;
    }

    /**
     * Reads the JSON changelog file for the given branch and short SHA.
     *
     * @param branch   the branch name used as the directory component.
     * @param shortSha the 7-character short SHA used as the file name prefix.
     * @return the parsed {@link ChangelogDocument}, or {@code null} if the file
     *         does not exist.
     * @throws IOException if the file exists but cannot be read or parsed.
     */
    public ChangelogDocument read(String branch, String shortSha) throws IOException {
        checkNotNull(branch, "branch must not be null");
        checkNotNull(shortSha, "shortSha must not be null");

        Path file = jsonChangelogDir(branch).resolve(shortSha + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        String json = Files.readString(file);
        return gson.fromJson(json, ChangelogDocument.class);
    }

    /**
     * Reads every JSON changelog persisted for the given branch.
     *
     * <p>Used by consumers that need a branch's full recorded change history
     * rather than one specific commit — e.g. the agentic advisor's
     * element-history tool, which retrieves all past changes touching a
     * conflicting element on its own. Files that cannot be parsed are skipped
     * with a warning so one corrupt changelog never hides the rest.
     *
     * @param branch the branch whose changelogs to read.
     * @return the parsed documents (unordered); empty when the branch has no
     *         changelog directory.
     * @throws IOException if the changelog directory exists but cannot be listed.
     */
    public List<ChangelogDocument> readAll(String branch) throws IOException {
        checkNotNull(branch, "branch must not be null");
        Path jsonDir = jsonChangelogDir(branch);
        if (!Files.isDirectory(jsonDir)) {
            return List.of();
        }
        List<ChangelogDocument> documents = new ArrayList<>();
        try (var files = Files.list(jsonDir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    ChangelogDocument document = gson.fromJson(Files.readString(file), ChangelogDocument.class);
                    if (document != null) {
                        documents.add(document);
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Skipping unreadable changelog {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
        return documents;
    }

    private Path jsonChangelogDir(String branch) {
        return repositoryRoot.resolve(".vitruvius").resolve("changelogs").resolve(branch).resolve("json");
    }

    private ChangelogDocument buildDocument(String commitSha, String branch, String author, LocalDateTime authorDate,
            String message, List<String> parentShas, Map<String, List<EChange<EObject>>> changesByResource,
            EChangeToEntryConverter converter) {
        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = FORMAT_VERSION;

        // Commit metadata
        doc.commit = new ChangelogDocument.CommitInfo();
        doc.commit.sha = commitSha;
        doc.commit.shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        doc.commit.branch = branch;
        doc.commit.message = message;
        doc.commit.parentShas = parentShas != null ? parentShas : List.of();

        if (author != null) {
            doc.commit.author = new ChangelogDocument.PersonInfo();
            doc.commit.author.name = author;
            doc.commit.author.date = authorDate != null ? authorDate.format(DATE_FORMATTER) : null;
        }

        // File changes with semantic entries
        doc.fileChanges = new ArrayList<>();
        int totalSemantic = 0;
        List<String> allUuids = new ArrayList<>();

        for (Map.Entry<String, List<EChange<EObject>>> entry : changesByResource.entrySet()) {
            String resourceUri = entry.getKey();
            List<EChange<EObject>> eChanges = entry.getValue();

            List<SemanticChangeEntry> entries = converter.convert(eChanges);
            totalSemantic += entries.size();

            entries.stream().filter(e -> e.getElementUuid() != null && !e.getElementUuid().equals("unknown"))
                    .map(SemanticChangeEntry::getElementUuid).filter(uuid -> !allUuids.contains(uuid))
                    .forEach(allUuids::add);

            ChangelogDocument.FileChangeInfo fileInfo = new ChangelogDocument.FileChangeInfo();
            fileInfo.operation = detectOperation(resourceUri, eChanges).name();
            fileInfo.path = toRelativePath(resourceUri);
            fileInfo.semanticChanges = entries;
            doc.fileChanges.add(fileInfo);
        }

        // Not available in the plain (non-annotated) path — origins are unknown
        doc.consequentialFootprints = null;

        // Summary
        doc.summary = new ChangelogDocument.Summary();
        doc.summary.totalFileChanges = doc.fileChanges.size();
        doc.summary.totalSemanticChanges = totalSemantic;
        doc.summary.affectedElementUuids = allUuids;

        return doc;
    }

    private ChangelogDocument buildDocumentAnnotated(String commitSha, String branch, String author, LocalDateTime authorDate,
            String message, List<String> parentShas, Map<String, List<SemanticChangeBuffer.AnnotatedEChange>> changesByResource,
            EChangeToEntryConverter converter) {
        ChangelogDocument doc = new ChangelogDocument();
        doc.formatVersion = FORMAT_VERSION;

        // Commit metadata
        doc.commit = new ChangelogDocument.CommitInfo();
        doc.commit.sha = commitSha;
        doc.commit.shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        doc.commit.branch = branch;
        doc.commit.message = message;
        doc.commit.parentShas = parentShas != null ? parentShas : List.of();

        if (author != null) {
            doc.commit.author = new ChangelogDocument.PersonInfo();
            doc.commit.author.name = author;
            doc.commit.author.date = authorDate != null ? authorDate.format(DATE_FORMATTER) : null;
        }

        // File changes with semantic entries
        doc.fileChanges = new ArrayList<>();
        int totalSemantic = 0;
        List<String> allUuids = new ArrayList<>();

        for (Map.Entry<String, List<SemanticChangeBuffer.AnnotatedEChange>> entry : changesByResource.entrySet()) {
            String resourceUri = entry.getKey();
            List<SemanticChangeBuffer.AnnotatedEChange> annotatedChanges = entry.getValue();

            List<SemanticChangeEntry> entries = converter.convertAnnotated(annotatedChanges);
            totalSemantic += entries.size();

            entries.stream().filter(e -> e.getElementUuid() != null && !e.getElementUuid().equals("unknown"))
                    .map(SemanticChangeEntry::getElementUuid).filter(uuid -> !allUuids.contains(uuid))
                    .forEach(allUuids::add);

            ChangelogDocument.FileChangeInfo fileInfo = new ChangelogDocument.FileChangeInfo();
            List<EChange<EObject>> rawEChanges = annotatedChanges.stream().map(SemanticChangeBuffer.AnnotatedEChange::getChange).toList();
            fileInfo.operation = detectOperation(resourceUri, rawEChanges).name();
            fileInfo.path = toRelativePath(resourceUri);
            fileInfo.semanticChanges = entries;
            doc.fileChanges.add(fileInfo);
        }

        // Consequential footprints — the (uuid, eClass, feature) triples written by
        // Reactions. De-duplicated via LinkedHashSet to maintain a stable order while
        // discarding repeated pairs (e.g. when two Reactions write the same feature).
        java.util.LinkedHashSet<ConsequentialFootprint> footprintSet = new java.util.LinkedHashSet<>();
        for (List<SemanticChangeBuffer.AnnotatedEChange> changes : changesByResource.values()) {
            for (SemanticChangeBuffer.AnnotatedEChange annotated : changes) {
                if (annotated.getOrigin() == ChangeOrigin.CONSEQUENTIAL) {
                    List<SemanticChangeEntry> entries = converter.convertAnnotated(List.of(annotated));
                    if (!entries.isEmpty()) {
                        SemanticChangeEntry e = entries.get(0);
                        if (e.getElementUuid() != null && !e.getElementUuid().equals("unknown")
                                && e.getFeature() != null) {
                            footprintSet.add(new ConsequentialFootprint(
                                    e.getElementUuid(), e.getEClass(), e.getFeature()));
                        }
                    }
                }
            }
        }
        doc.consequentialFootprints = new ArrayList<>(footprintSet);

        // Summary
        doc.summary = new ChangelogDocument.Summary();
        doc.summary.totalFileChanges = doc.fileChanges.size();
        doc.summary.totalSemanticChanges = totalSemantic;
        doc.summary.affectedElementUuids = allUuids;

        return doc;
    }

    /**
     * Finds the Resource whose URI string matches the given resourceUri from the
     * active resources.
     */
    private Resource findResource(Collection<Resource> resources, String resourceUri) {
        for (Resource r : resources) {
            if (r.getURI() != null && resourceUri.equals(r.getURI().toString())) {
                return r;
            }
        }
        return null;
    }

    /**
     * Derives a short, file-system-safe name from a resource URI for use in XMI
     * file names.
     */
    private String deriveResourceName(String resourceUri) {
        try {
            URI uri = URI.createURI(resourceUri);
            String lastSegment = uri.lastSegment();
            if (lastSegment != null && !lastSegment.isBlank()) {
                // Strip extension
                int dot = lastSegment.lastIndexOf('.');
                return dot > 0 ? lastSegment.substring(0, dot) : lastSegment;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not derive resource name from URI '{}': {}", resourceUri, e.getMessage());
        }
        // Fallback: sanitize the URI string
        return resourceUri.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Infers the file-level operation from the types of EChanges present.
     * If only create changes are present the file was likely ADDED; if only
     * deletes, DELETED;
     * otherwise MODIFIED.
     */
    private FileOperation detectOperation(String resourceUri, List<EChange<EObject>> eChanges) {
        boolean hasCreate = eChanges.stream()
                .anyMatch(c -> c instanceof tools.vitruv.change.atomic.eobject.CreateEObject<?>);
        boolean hasDelete = eChanges.stream()
                .anyMatch(c -> c instanceof tools.vitruv.change.atomic.eobject.DeleteEObject<?>);
        if (hasCreate && !hasDelete)
            return FileOperation.ADDED;
        if (hasDelete && !hasCreate)
            return FileOperation.DELETED;
        return FileOperation.MODIFIED;
    }

    /**
     * Converts an absolute resource URI to a repository-relative path string.
     * Falls back to the URI string itself for non-file URIs.
     */
    private String toRelativePath(String resourceUri) {
        try {
            URI uri = URI.createURI(resourceUri);
            if (uri.isFile()) {
                Path absolute = Path.of(uri.toFileString());
                if (absolute.startsWith(repositoryRoot)) {
                    return repositoryRoot.relativize(absolute).toString().replace('\\', '/');
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not relativize URI '{}': {}", resourceUri, e.getMessage());
        }
        return resourceUri;
    }

    private Gson buildGson() {
        return new GsonBuilder().setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonSerializer<LocalDateTime>) (src, type, ctx) -> new JsonPrimitive(src.format(DATE_FORMATTER)))
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonDeserializer<LocalDateTime>) (json, type, ctx) ->
                                LocalDateTime.parse(json.getAsString(), DATE_FORMATTER))
                .registerTypeAdapter(ChangeOrigin.class,
                        (JsonSerializer<ChangeOrigin>) (src, type, ctx) -> new JsonPrimitive(src.name()))
                .registerTypeAdapter(ChangeOrigin.class,
                        (JsonDeserializer<ChangeOrigin>) (json, type, ctx) -> {
                            try {
                                return ChangeOrigin.valueOf(json.getAsString());
                            } catch (IllegalArgumentException e) {
                                return ChangeOrigin.UNKNOWN;
                            }
                        })
                // ConsequentialFootprint is a Java record — Gson handles it as a POJO
                // automatically; the explicit adapter makes de-serialisation robust against
                // missing fields in older changelog files.
                .registerTypeAdapter(ConsequentialFootprint.class,
                        (JsonDeserializer<ConsequentialFootprint>) (json, type, ctx) -> {
                            var obj = json.getAsJsonObject();
                            String uuid = obj.has("elementUuid") ? obj.get("elementUuid").getAsString() : "unknown";
                            String eClass = obj.has("eClass") ? obj.get("eClass").getAsString() : null;
                            String feature = obj.has("feature") ? obj.get("feature").getAsString() : "unknown";
                            return new ConsequentialFootprint(uuid, eClass, feature);
                        })
                .create();
    }

    /**
     * Root JSON document written to
     * {@code .vitruvius/changelogs/<branch>/json/<shortSha>.json}.
     * Field names are intentionally kept in camelCase to match the agreed schema.
     */
    public static class ChangelogDocument {
        public String formatVersion;
        public CommitInfo commit;
        public List<FileChangeInfo> fileChanges;
        /**
         * Footprint of all element-feature pairs written by Reactions during this
         * commit. Used by the dependency-graph construction in the semantic merge
         * engine (paper section 4.8) to build inter-branch ordering edges without
         * requiring live replay. Populated only when the changelog is written via
         * {@link SemanticChangelogManager#writeAnnotated}; absent (null) in
         * changelogs written by the plain {@link SemanticChangelogManager#write}.
         */
        public List<ConsequentialFootprint> consequentialFootprints;
        public Summary summary;

        public static class CommitInfo {
            public String sha;
            public String shortSha;
            public String branch;
            public PersonInfo author;
            public PersonInfo committer;
            public String message;
            public List<String> parentShas;
        }

        public static class PersonInfo {
            public String name;
            public String email;
            public String date;
        }

        public static class FileChangeInfo {
            public String operation;
            public String path;
            public String oldPath;
            public List<SemanticChangeEntry> semanticChanges;
        }

        public static class Summary {
            public int totalFileChanges;
            public int totalSemanticChanges;
            public List<String> affectedElementUuids;
        }
    }
}
