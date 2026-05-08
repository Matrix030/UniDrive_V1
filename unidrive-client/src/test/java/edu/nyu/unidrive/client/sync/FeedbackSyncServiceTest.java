package edu.nyu.unidrive.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.net.SubmissionApiClient;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.dto.SubmissionUploadResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackSyncServiceTest {

    @Test
    void studentFeedbackSyncDownloadsIntoAssignmentFeedbackFolder(@TempDir Path tempDir) throws IOException {
        byte[] content = "nice work".getBytes();
        String sha256 = FileHasher.sha256Hex(content);
        RecordingFeedbackApiClient apiClient = new RecordingFeedbackApiClient(List.of(new FeedbackSummaryResponse(
            "feedback-1",
            "submission-1",
            "fall2026",
            "daa",
            "hw1",
            "rvg9395",
            "comments.txt",
            sha256
        )));
        apiClient.downloads.put("feedback-1", new DownloadedFile("comments.txt", content));

        ReceivedStateRepository repository = new ReceivedStateRepository(tempDir.resolve("received.db"));

        int downloaded = new FeedbackSyncService(apiClient, repository).syncFeedback("rvg9395", tempDir);

        Path feedbackFile = tempDir.resolve("fall2026/daa/hw1/feedback/comments.txt");
        assertEquals(1, downloaded);
        assertTrue(Files.exists(feedbackFile));
        assertEquals("nice work", Files.readString(feedbackFile));
        assertEquals(SyncStatus.SYNCED, repository.findByLocalPath(feedbackFile).orElseThrow().status());
    }

    @Test
    void studentFeedbackSyncDeletesStaleLocalFeedback(@TempDir Path tempDir) throws IOException {
        Path staleFeedback = tempDir.resolve("fall2026/daa/hw1/feedback/old.txt");
        Files.createDirectories(staleFeedback.getParent());
        Files.writeString(staleFeedback, "old feedback");
        ReceivedStateRepository repository = new ReceivedStateRepository(tempDir.resolve("received.db"));

        int downloaded = new FeedbackSyncService(new RecordingFeedbackApiClient(List.of()), repository)
            .syncFeedback("rvg9395", tempDir);

        assertEquals(0, downloaded);
        assertFalse(Files.exists(staleFeedback));
        assertTrue(repository.findByLocalPath(staleFeedback).isEmpty());
    }

    @Test
    void instructorFeedbackSyncUploadsFilesFromStudentFeedbackFolder(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = tempDir.resolve("instructor");
        Path feedbackDir = tempDir.resolve("fall2026/daa/hw1/submissions/student_rvg9395/feedback");
        feedbackDir = workspaceRoot.resolve("fall2026/daa/hw1/submissions/student_rvg9395/feedback");
        Files.createDirectories(feedbackDir);
        Path feedbackFile = feedbackDir.resolve("comments.txt");
        Files.writeString(feedbackFile, "try again");

        RecordingFeedbackApiClient apiClient = new RecordingFeedbackApiClient(List.of());
        SyncStateRepository repository = new SyncStateRepository(tempDir.resolve("sync.db"));
        RecordingSubmissionApiClient submissionApiClient = new RecordingSubmissionApiClient(List.of(new SubmissionSummaryResponse(
            "submission-1",
            "fall2026",
            "daa",
            "hw1",
            "rvg9395",
            "Solution.java",
            "solution-hash",
            SyncStatus.SYNCED.name()
        )));

        FeedbackDirectoryWatcher watcher = new FeedbackDirectoryWatcher(workspaceRoot);
        try {
            new InstructorFeedbackSyncService(
                watcher,
                new FeedbackUploadService(repository, apiClient, submissionApiClient, workspaceRoot),
                new FeedbackReconcileService(repository),
                repository,
                workspaceRoot,
                Duration.ZERO
            ).processOnce();
        } finally {
            watcher.close();
        }

        assertEquals(List.of("submission-1:comments.txt"), apiClient.uploads);
        assertEquals(SyncStatus.SYNCED, repository.findByLocalPath(feedbackFile).orElseThrow().status());
    }

    @Test
    void instructorFeedbackSyncDeletesRemoteFeedbackWhenLocalFileIsRemoved(@TempDir Path tempDir) throws IOException {
        Path workspaceRoot = tempDir.resolve("instructor");
        Path feedbackFile = workspaceRoot.resolve("fall2026/daa/hw1/submissions/student_rvg9395/feedback/comments.txt");
        SyncStateRepository repository = new SyncStateRepository(tempDir.resolve("sync.db"));
        repository.save(new edu.nyu.unidrive.client.storage.SyncStateRecord(
            feedbackFile,
            "feedback-1",
            "old-hash",
            SyncStatus.SYNCED,
            123L
        ));
        RecordingFeedbackApiClient apiClient = new RecordingFeedbackApiClient(List.of());

        FeedbackDirectoryWatcher watcher = new FeedbackDirectoryWatcher(workspaceRoot);
        try {
            new InstructorFeedbackSyncService(
                watcher,
                new FeedbackUploadService(repository, apiClient, new RecordingSubmissionApiClient(List.of()), workspaceRoot),
                new FeedbackReconcileService(repository),
                repository,
                workspaceRoot,
                Duration.ZERO
            ).processOnce();
        } finally {
            watcher.close();
        }

        assertEquals(List.of("feedback-1"), apiClient.deletes);
        assertTrue(repository.findByLocalPath(feedbackFile).isEmpty());
    }

    private static final class RecordingSubmissionApiClient implements SubmissionApiClient {

        private final List<SubmissionSummaryResponse> submissions;

        private RecordingSubmissionApiClient(List<SubmissionSummaryResponse> submissions) {
            this.submissions = submissions;
        }

        @Override
        public SubmissionUploadResponse uploadSubmission(CoursePath coursePath, String studentId, Path filePath, String sha256) {
            return null;
        }

        @Override
        public List<SubmissionSummaryResponse> listSubmissions(CoursePath coursePath) {
            return submissions;
        }

        @Override
        public DownloadedFile downloadSubmission(String submissionId) {
            return null;
        }

        @Override
        public void deleteSubmission(String submissionId) {
        }
    }

    private static final class RecordingFeedbackApiClient implements FeedbackApiClient {

        private final List<FeedbackSummaryResponse> feedback;
        private final Map<String, DownloadedFile> downloads = new LinkedHashMap<>();
        private final List<String> uploads = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();

        private RecordingFeedbackApiClient(List<FeedbackSummaryResponse> feedback) {
            this.feedback = feedback;
        }

        @Override
        public List<FeedbackSummaryResponse> listFeedback(String studentId) {
            return feedback;
        }

        @Override
        public DownloadedFile downloadFeedback(String feedbackId) {
            return downloads.get(feedbackId);
        }

        @Override
        public FeedbackSummaryResponse uploadFeedback(String submissionId, Path file) throws IOException {
            uploads.add(submissionId + ":" + file.getFileName());
            return new FeedbackSummaryResponse(
                "feedback-" + uploads.size(),
                submissionId,
                "fall2026",
                "daa",
                "hw1",
                "rvg9395",
                file.getFileName().toString(),
                FileHasher.sha256Hex(file)
            );
        }

        @Override
        public void deleteFeedback(String feedbackId) {
            deletes.add(feedbackId);
        }
    }
}
