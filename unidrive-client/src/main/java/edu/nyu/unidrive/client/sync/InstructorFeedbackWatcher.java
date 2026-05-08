package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.SyncServiceHandle;
import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.storage.ReceivedStateRecord;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class InstructorFeedbackWatcher implements SyncServiceHandle {

    private final FeedbackApiClient feedbackApiClient;
    private final ReceivedStateRepository receivedStateRepository;
    private final Map<Path, String> latestSubmissionByFeedbackDirectory;
    private final Duration pollInterval;
    private final Map<Path, String> uploadedHashes = new HashMap<>();
    private Thread workerThread;

    public InstructorFeedbackWatcher(
        FeedbackApiClient feedbackApiClient,
        ReceivedStateRepository receivedStateRepository,
        Map<Path, String> latestSubmissionByFeedbackDirectory,
        Duration pollInterval
    ) {
        this.feedbackApiClient = feedbackApiClient;
        this.receivedStateRepository = receivedStateRepository;
        this.latestSubmissionByFeedbackDirectory = latestSubmissionByFeedbackDirectory;
        this.pollInterval = pollInterval;
    }

    @Override
    public synchronized void start() {
        if (workerThread != null) {
            return;
        }
        workerThread = new Thread(this::runLoop, "unidrive-instructor-feedback-watcher");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void processOnce() {
        latestSubmissionByFeedbackDirectory.forEach(this::processFeedbackDirectory);
    }

    @Override
    public synchronized void close() {
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void processFeedbackDirectory(Path feedbackDir, String submissionId) {
        if (submissionId == null || !Files.isDirectory(feedbackDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(feedbackDir)) {
            files.filter(Files::isRegularFile).forEach(file -> uploadIfNew(submissionId, file));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan feedback directory " + feedbackDir, exception);
        }
    }

    private void uploadIfNew(String submissionId, Path file) {
        try {
            String sha256 = FileHasher.sha256Hex(file);
            Path normalizedFile = file.toAbsolutePath().normalize();
            ReceivedStateRecord existingRecord = receivedStateRepository.findByLocalPath(file).orElse(null);
            if (sha256.equals(uploadedHashes.get(normalizedFile)) || isAlreadySynced(existingRecord, sha256)) {
                return;
            }
            receivedStateRepository.save(new ReceivedStateRecord(
                file,
                null,
                null,
                SyncStatus.PENDING,
                0L,
                ReceivedReconcileService.SOURCE_INSTRUCTOR_FEEDBACKS
            ));

            var response = feedbackApiClient.uploadFeedback(submissionId, file);
            uploadedHashes.put(normalizedFile, sha256);
            receivedStateRepository.save(new ReceivedStateRecord(
                file,
                response.getFeedbackId(),
                response.getSha256(),
                SyncStatus.SYNCED,
                System.currentTimeMillis(),
                ReceivedReconcileService.SOURCE_INSTRUCTOR_FEEDBACKS
            ));
        } catch (IOException exception) {
            receivedStateRepository.save(new ReceivedStateRecord(
                file,
                null,
                null,
                SyncStatus.FAILED,
                0L,
                ReceivedReconcileService.SOURCE_INSTRUCTOR_FEEDBACKS
            ));
            throw new IllegalStateException("Failed to upload feedback file " + file, exception);
        }
    }

    private boolean isAlreadySynced(ReceivedStateRecord existingRecord, String sha256) {
        return existingRecord != null
            && existingRecord.status() == SyncStatus.SYNCED
            && sha256.equals(existingRecord.sha256())
            && ReceivedReconcileService.SOURCE_INSTRUCTOR_FEEDBACKS.equals(existingRecord.source());
    }

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                processOnce();
            } catch (RuntimeException ignored) {
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
