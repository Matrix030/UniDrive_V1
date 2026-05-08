package edu.nyu.unidrive.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.client.net.AssignmentApiClient;
import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssignmentSyncServiceTest {

    @Test
    void syncMigratesLegacyStudentPublishFolderBeforeRecordingAssignment(@TempDir Path tempDir) throws IOException {
        Path legacyFile = tempDir.resolve("fall2026/daa/hw1/publish/spec.md");
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile, "legacy spec");
        String sha256 = FileHasher.sha256Hex(legacyFile);

        ReceivedStateRepository repository = new ReceivedStateRepository(tempDir.resolve(".unidrive/received.db"));
        repository.save(new ReceivedStateRecord(
            legacyFile,
            "assignment:spec.md",
            sha256,
            SyncStatus.SYNCED,
            111L,
            ReceivedReconcileService.SOURCE_ASSIGNMENTS
        ));
        RecordingAssignmentApiClient apiClient = new RecordingAssignmentApiClient(List.of(
            new AssignmentSummaryResponse("hw1", "fall2026", "daa", "Assignment 1", "spec.md", sha256)
        ));

        int downloadedCount = new AssignmentSyncService(apiClient, repository)
            .syncAssignmentsForCourse("fall2026", "daa", tempDir);

        Path migratedFile = tempDir.resolve("fall2026/daa/hw1/files/spec.md");
        assertEquals(0, downloadedCount);
        assertEquals(0, apiClient.downloadCount);
        assertTrue(Files.exists(migratedFile));
        assertFalse(Files.exists(legacyFile));
        assertFalse(Files.exists(legacyFile.getParent()));
        assertTrue(repository.findByLocalPath(legacyFile).isEmpty());
        assertEquals(SyncStatus.SYNCED, repository.findByLocalPath(migratedFile).orElseThrow().status());
    }

    @Test
    void syncMovesConflictingLegacyPublishFileOutOfParsedAssignmentFolders(@TempDir Path tempDir) throws IOException {
        Path legacyFile = tempDir.resolve("fall2026/daa/hw1/publish/spec.md");
        Path currentFile = tempDir.resolve("fall2026/daa/hw1/files/spec.md");
        Files.createDirectories(legacyFile.getParent());
        Files.createDirectories(currentFile.getParent());
        Files.writeString(legacyFile, "student edits");
        Files.writeString(currentFile, "server version");
        String sha256 = FileHasher.sha256Hex(currentFile);

        ReceivedStateRepository repository = new ReceivedStateRepository(tempDir.resolve(".unidrive/received.db"));
        RecordingAssignmentApiClient apiClient = new RecordingAssignmentApiClient(List.of(
            new AssignmentSummaryResponse("hw1", "fall2026", "daa", "Assignment 1", "spec.md", sha256)
        ));

        int downloadedCount = new AssignmentSyncService(apiClient, repository)
            .syncAssignmentsForCourse("fall2026", "daa", tempDir);

        Path conflictFile = tempDir.resolve("fall2026/daa/hw1/.legacy-publish/spec.md");
        assertEquals(0, downloadedCount);
        assertTrue(Files.exists(currentFile));
        assertTrue(Files.exists(conflictFile));
        assertFalse(Files.exists(legacyFile));
        assertTrue(CoursePath.parseFromWorkspace(tempDir, conflictFile).isEmpty());
    }

    @Test
    void syncDeletesStudentAddedFilesFromFilesFolder(@TempDir Path tempDir) throws IOException {
        Path professorFile = tempDir.resolve("fall2026/daa/hw1/files/spec.md");
        Path studentAddedFile = tempDir.resolve("fall2026/daa/hw1/files/notes.md");
        Files.createDirectories(professorFile.getParent());
        Files.writeString(professorFile, "server version");
        Files.writeString(studentAddedFile, "student notes");
        String sha256 = FileHasher.sha256Hex(professorFile);

        ReceivedStateRepository repository = new ReceivedStateRepository(tempDir.resolve(".unidrive/received.db"));
        repository.save(new ReceivedStateRecord(
            studentAddedFile,
            "assignment:notes.md",
            "student-hash",
            SyncStatus.SYNCED,
            111L,
            ReceivedReconcileService.SOURCE_ASSIGNMENTS
        ));
        RecordingAssignmentApiClient apiClient = new RecordingAssignmentApiClient(List.of(
            new AssignmentSummaryResponse("hw1", "fall2026", "daa", "Assignment 1", "spec.md", sha256)
        ));

        int downloadedCount = new AssignmentSyncService(apiClient, repository)
            .syncAssignmentsForCourse("fall2026", "daa", tempDir);

        assertEquals(0, downloadedCount);
        assertTrue(Files.exists(professorFile));
        assertFalse(Files.exists(studentAddedFile));
        assertTrue(repository.findByLocalPath(studentAddedFile).isEmpty());
    }

    @Test
    void syncOverwritesModifiedProfessorFileInFilesFolder(@TempDir Path tempDir) throws IOException {
        Path professorFile = tempDir.resolve("fall2026/daa/hw1/files/spec.md");
        Files.createDirectories(professorFile.getParent());
        Files.writeString(professorFile, "student changed professor file");
        byte[] serverContent = "server version".getBytes();
        String sha256 = FileHasher.sha256Hex(serverContent);

        RecordingAssignmentApiClient apiClient = new RecordingAssignmentApiClient(
            List.of(new AssignmentSummaryResponse("hw1", "fall2026", "daa", "Assignment 1", "spec.md", sha256)),
            Map.of("hw1/spec.md", serverContent)
        );

        int downloadedCount = new AssignmentSyncService(apiClient, new ReceivedStateRepository(tempDir.resolve(".unidrive/received.db")))
            .syncAssignmentsForCourse("fall2026", "daa", tempDir);

        assertEquals(1, downloadedCount);
        assertEquals("server version", Files.readString(professorFile));
    }

    private static final class RecordingAssignmentApiClient implements AssignmentApiClient {

        private final List<AssignmentSummaryResponse> assignments;
        private final Map<String, byte[]> downloads;
        private int downloadCount;

        private RecordingAssignmentApiClient(List<AssignmentSummaryResponse> assignments) {
            this(assignments, new HashMap<>());
        }

        private RecordingAssignmentApiClient(List<AssignmentSummaryResponse> assignments, Map<String, byte[]> downloads) {
            this.assignments = assignments;
            this.downloads = downloads;
        }

        @Override
        public List<AssignmentSummaryResponse> listAssignments(String term, String courseSlug) {
            return assignments;
        }

        @Override
        public DownloadedFile downloadAssignment(String assignmentId, String fileName) {
            downloadCount++;
            return new DownloadedFile(fileName, downloads.getOrDefault(assignmentId + "/" + fileName, "downloaded".getBytes()));
        }

        @Override
        public AssignmentSummaryResponse publishAssignment(CoursePath coursePath, String title, String deadline, Path file) {
            return null;
        }

        @Override
        public void deleteAssignment(String assignmentId, String fileName) {
        }
    }
}
