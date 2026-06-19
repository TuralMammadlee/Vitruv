package tools.vitruv.framework.vsum.branch.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves Git authors for conflicting line ranges by running blame against both
 * merge participants (source and target revisions).
 */
public class ConflictOwnerResolver {

    private static final Logger LOGGER = LogManager.getLogger(ConflictOwnerResolver.class);

    /**
     * Resolves owners per conflicting file.
     *
     * @param repository   repository used for blame.
     * @param sourceCommit source branch tip involved in the merge.
     * @param targetCommit target branch tip involved in the merge (usually HEAD).
     * @param conflicts    merge conflict map from JGit (path -> conflict ranges).
     * @return map of file path -> detected owner emails (lowercase).
     */
    public Map<String, Set<String>> resolveConflictOwners(Repository repository,
                                                           ObjectId sourceCommit,
                                                           ObjectId targetCommit,
                                                           Map<String, int[][]> conflicts) {
        Objects.requireNonNull(repository, "repository must not be null");
        if (sourceCommit == null || targetCommit == null || conflicts == null || conflicts.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> ownersByFile = new LinkedHashMap<>();
        for (Map.Entry<String, int[][]> entry : conflicts.entrySet()) {
            String filePath = entry.getKey();
            if (filePath == null || filePath.isBlank()) {
                continue;
            }

            Set<Integer> lineHints = extractLineHints(entry.getValue());
            Set<String> owners = new LinkedHashSet<>();
            owners.addAll(blameOwners(repository, sourceCommit, filePath, lineHints));
            owners.addAll(blameOwners(repository, targetCommit, filePath, lineHints));

            if (!owners.isEmpty()) {
                ownersByFile.put(filePath, Set.copyOf(owners));
            }
        }
        return Collections.unmodifiableMap(ownersByFile);
    }

    private Set<String> blameOwners(Repository repository, ObjectId revision, String filePath, Set<Integer> lineHints) {
        Set<String> owners = new LinkedHashSet<>();
        try {
            BlameResult blame = new BlameCommand(repository)
                    .setStartCommit(revision)
                    .setFilePath(filePath)
                    .call();
            if (blame == null || blame.getResultContents() == null) {
                return owners;
            }

            int lineCount = blame.getResultContents().size();
            boolean useHints = !lineHints.isEmpty();

            for (int i = 0; i < lineCount; i++) {
                if (useHints && !lineHints.contains(i) && !lineHints.contains(i + 1)) {
                    continue;
                }
                PersonIdent author = blame.getSourceAuthor(i);
                if (author == null) {
                    continue;
                }
                String email = normalizeEmail(author.getEmailAddress());
                if (email != null) {
                    owners.add(email);
                }
            }

            if (owners.isEmpty() && useHints) {
                // Fallback when conflict ranges and blame line indexing do not align.
                for (int i = 0; i < lineCount; i++) {
                    PersonIdent author = blame.getSourceAuthor(i);
                    if (author == null) {
                        continue;
                    }
                    String email = normalizeEmail(author.getEmailAddress());
                    if (email != null) {
                        owners.add(email);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Owner blame failed for '{}' at {}: {}", filePath, revision.name(), e.getMessage());
        }
        return owners;
    }

    private Set<Integer> extractLineHints(int[][] rawRanges) {
        Set<Integer> hints = new LinkedHashSet<>();
        if (rawRanges == null) {
            return hints;
        }

        for (int[] range : rawRanges) {
            if (range == null) {
                continue;
            }
            for (int value : range) {
                if (value > 0) {
                    hints.add(value);
                }
            }
            if (range.length >= 2 && range[0] > 0 && range[1] > 0) {
                int start = range[0];
                int second = range[1];
                int maxWindow = 25;
                if (second > start && (second - start) <= maxWindow) {
                    for (int line = start; line <= second; line++) {
                        hints.add(line);
                    }
                } else if (second <= maxWindow) {
                    for (int line = start; line < start + second; line++) {
                        hints.add(line);
                    }
                }
            }
        }
        return hints;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }
}
