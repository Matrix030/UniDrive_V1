package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.net.DownloadedFile;
import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            for (FeedbackSummaryResponse feedback : feedbackApiClient.listFeedback(studentId)) {
                CoursePath coursePath = new CoursePath(feedback.getTerm(), feedback.getCourse(), feedback.getAssignmentId());
                Path feedbackDirectory = coursePath.feedbackDirIn(workspaceRoot);
                Files.createDirectories(feedbackDirectory);
                Path destination = feedbackDirectory.resolve(feedback.getFileName());
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
                Files.write(feedbackDirectory.resolve(download.fileName()), download.content());
                receivedStateRepository.save(new ReceivedStateRecord(
                    destination,
                    feedback.getFeedbackId(),
                    feedback.getSha256(),
                    SyncStatus.SYNCED,
                    System.currentTimeMillis(),
                    ReceivedReconcileService.SOURCE_FEEDBACK
                ));
                downloadedCount++;
            }
            return downloadedCount;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to synchronize feedback.", exception);
        }
    }
}
