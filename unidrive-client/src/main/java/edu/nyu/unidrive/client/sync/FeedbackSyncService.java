package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class FeedbackSyncService {

    private final FeedbackApiClient feedbackApiClient;
    private final ReceivedStateRepository receivedStateRepository;

    public FeedbackSyncService(FeedbackApiClient feedbackApiClient, ReceivedStateRepository receivedStateRepository) {
        this.feedbackApiClient = feedbackApiClient;
        this.receivedStateRepository = receivedStateRepository;
    }

    public int syncFeedback(String studentId, Path workspaceRoot) {
        try {
            int downloadedCount = 0;
            Map<Path, FeedbackSummaryResponse> expectedFeedback = expectedFeedbackByPath(studentId, workspaceRoot);
            for (Map.Entry<Path, FeedbackSummaryResponse> entry : expectedFeedback.entrySet()) {
                Path destination = entry.getKey();
                FeedbackSummaryResponse feedback = entry.getValue();
                CoursePath coursePath = new CoursePath(feedback.getTerm(), feedback.getCourse(), feedback.getAssignmentId());
                Path feedbackDirectory = coursePath.feedbackDirIn(workspaceRoot);
                Files.createDirectories(feedbackDirectory);
                if (Files.exists(destination) && FileHasher.sha256Hex(destination).equals(feedback.getSha256())) {
                    receivedStateRepository.save(new ReceivedStateRecord(
                        destination,
                        feedback.getFeedbackId(),
                        feedback.getSha256(),
                        SyncStatus.SYNCED,
                        System.currentTimeMillis(),
                        ReceivedReconcileService.SOURCE_FEEDBACK
                    ));
                    continue;
                }

                receivedStateRepository.save(new ReceivedStateRecord(
                    destination,
                    feedback.getFeedbackId(),
                    feedback.getSha256(),
                    SyncStatus.PENDING,
                    0L,
                    ReceivedReconcileService.SOURCE_FEEDBACK
                ));

                DownloadedFile download = feedbackApiClient.downloadFeedback(feedback.getFeedbackId());
                Path downloadedDestination = feedbackDirectory.resolve(download.fileName());
                Files.createDirectories(downloadedDestination.getParent());
                Files.write(downloadedDestination, download.content());
                receivedStateRepository.save(new ReceivedStateRecord(
                    downloadedDestination,
                    feedback.getFeedbackId(),
                    feedback.getSha256(),
                    SyncStatus.SYNCED,
                    System.currentTimeMillis(),
                    ReceivedReconcileService.SOURCE_FEEDBACK
                ));
                downloadedCount++;
            }
            removeStaleFeedbackFiles(workspaceRoot, expectedFeedback.keySet());
            return downloadedCount;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to synchronize feedback.", exception);
        }
    }

    private Map<Path, FeedbackSummaryResponse> expectedFeedbackByPath(String studentId, Path workspaceRoot) throws IOException {
        Map<Path, FeedbackSummaryResponse> expectedFeedback = new LinkedHashMap<>();
        for (FeedbackSummaryResponse feedback : feedbackApiClient.listFeedback(studentId)) {
            CoursePath coursePath = new CoursePath(feedback.getTerm(), feedback.getCourse(), feedback.getAssignmentId());
            Path destination = coursePath.feedbackDirIn(workspaceRoot).resolve(feedback.getFileName());
            expectedFeedback.putIfAbsent(destination, feedback);
        }
        return expectedFeedback;
    }

    private void removeStaleFeedbackFiles(Path workspaceRoot, Set<Path> expectedFiles) throws IOException {
        if (!Files.isDirectory(workspaceRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(workspaceRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!isStudentFeedbackFile(workspaceRoot, file)) {
                    continue;
                }
                if (!expectedFiles.contains(file)) {
                    Files.delete(file);
                    receivedStateRepository.deleteByLocalPath(file);
                }
            }
        }
    }

    private boolean isStudentFeedbackFile(Path workspaceRoot, Path file) {
        return CoursePath.parseFromWorkspace(workspaceRoot, file)
            .map(parsed -> parsed.leaf() == Leaf.FEEDBACK && parsed.studentId().isEmpty())
            .orElse(false);
    }
}
