package tools.vitruv.framework.vsum.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.vitruv.framework.vsum.branch.GitTestHelper.initRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VsumFileSystemLayoutTest {

  @Test
  @DisplayName("falls back to legacy layout when storage folder is not a Git repository")
  void fallsBackToLegacyLayoutWithoutGitRepo(@TempDir Path tempDir) throws Exception {
    Path storageFolder = tempDir.resolve("storage");
    Files.createDirectories(storageFolder);

    VsumFileSystemLayout layout = new VsumFileSystemLayout(storageFolder);
    layout.prepare();

    Path expected =
        storageFolder.resolve("vsum").resolve("uuid.uuid").toAbsolutePath().normalize();
    Path actual = Path.of(layout.getUuidsURI().toFileString()).toAbsolutePath().normalize();

    assertEquals(expected, actual);
    assertTrue(Files.isDirectory(storageFolder.resolve("vsum")));
  }

  @Test
  @DisplayName("uses branch-aware layout when storage folder is a Git repository")
  void usesBranchAwareLayoutWithGitRepo(@TempDir Path repoDir) throws Exception {
    try (var ignored = initRepo(repoDir)) {
      VsumFileSystemLayout layout = new VsumFileSystemLayout(repoDir);
      layout.prepare();

      Path expected =
          repoDir
              .resolve(".vitruvius")
              .resolve("vsum")
              .resolve("master")
              .resolve("uuid.uuid")
              .toAbsolutePath()
              .normalize();
      Path actual = Path.of(layout.getUuidsURI().toFileString()).toAbsolutePath().normalize();

      assertEquals("master", layout.getCurrentBranch());
      assertEquals(expected, actual);
      assertTrue(Files.isDirectory(repoDir.resolve(".vitruvius").resolve("vsum").resolve("master")));
    }
  }
}
