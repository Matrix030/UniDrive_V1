package edu.nyu.unidrive.client.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
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
    void instructorFeedbackWatcherUploadsFilesFromStudentFeedbackFolder(@TempDir Path tempDir) throws IOException {
        Path feedbackDir = tempDir.resolve("fall2026/daa/hw1/submissions/student_rvg9395/feedback");
        Files.createDirectories(feedbackDir);
        Path feedbackFile = feedbackDir.resolve("comments.txt");
        Files.writeString(feedbackFile, "try again");

        RecordingFeedbackApiClient apiClient = new RecordingFeedbackApiClient(List.of());
        ReceivedStateRepository repository = new ReceivedStateRepository(tempDir.resolve("received.db"));
        Map<Path, String> latestSubmissionByFeedbackDirectory = Map.of(feedbackDir.toAbsolutePath().normalize(), "submission-1");

        new InstructorFeedbackWatcher(
            apiClient,
            repository,
            latestSubmissionByFeedbackDirectory,
            Duration.ZERO
        ).processOnce();

        assertEquals(List.of("submission-1:comments.txt"), apiClient.uploads);
        assertEquals(SyncStatus.SYNCED, repository.findByLocalPath(feedbackFile).orElseThrow().status());
    }

    private static final class RecordingFeedbackApiClient implements FeedbackApiClient {

        private final List<FeedbackSummaryResponse> feedback;
        private final Map<String, DownloadedFile> downloads = new LinkedHashMap<>();
        private final List<String> uploads = new ArrayList<>();

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
    }
}
