package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.SyncServiceHandle;
import edu.nyu.unidrive.client.storage.SyncStateRecord;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public final class InstructorFeedbackSyncService implements SyncServiceHandle {

    private final FeedbackDirectoryWatcher watcher;
    private final FeedbackUploadService uploadService;
    private final FeedbackReconcileService reconcileService;
    private final SyncStateRepository syncStateRepository;
    private final Path workspaceRoot;
    private final Duration pollTimeout;
    private Thread workerThread;

    private static final int RETRY_SWEEP_EVERY_N_ITERATIONS = 20;
    private int loopIteration = 0;

    public InstructorFeedbackSyncService(
        FeedbackDirectoryWatcher watcher,
        FeedbackUploadService uploadService,
        FeedbackReconcileService reconcileService,
        SyncStateRepository syncStateRepository,
        Path workspaceRoot,
        Duration pollTimeout
    ) {
        this.watcher = watcher;
        this.uploadService = uploadService;
        this.reconcileService = reconcileService;
        this.syncStateRepository = syncStateRepository;
        this.workspaceRoot = workspaceRoot;
        this.pollTimeout = pollTimeout;
    }

    @Override
    public synchronized void start() {
        if (workerThread != null) {
            return;
        }
        workerThread = new Thread(this::runLoop, "unidrive-feedback-sync");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void processOnce() {
        uploadService.resetSubmissionCache();
        for (SubmissionFileEvent event : watcher.pollEvents(pollTimeout)) {
            if (event.type() == SubmissionFileEventType.OVERFLOW) {
                reconcileService.reconcileSubtree(workspaceRoot, event.path());
                continue;
            }
            if (event.type() == SubmissionFileEventType.DELETED) {
                uploadService.deleteFeedback(event.path());
            } else if (!isIgnoredFeedbackFile(event.path())) {
                recordPendingEvent(event.path());
                uploadService.uploadPendingFeedback(event.path());
            }
        }

        if (loopIteration++ % RETRY_SWEEP_EVERY_N_ITERATIONS != 0) {
            return;
        }
        for (SyncStateRecord row : syncStateRepository.findAll()) {
            if (!isInstructorFeedbackPath(row.localPath())) {
                continue;
            }
            if (!Files.exists(row.localPath())) {
                uploadService.deleteFeedback(row.localPath());
                continue;
            }
            if (row.status() != SyncStatus.PENDING && row.status() != SyncStatus.FAILED) {
                continue;
            }
            if (Files.isRegularFile(row.localPath()) && !isIgnoredFeedbackFile(row.localPath())) {
                uploadService.uploadPendingFeedback(row.localPath());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (workerThread != null) {
            workerThread.interrupt();
        }
        try {
            watcher.close();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to close feedback watcher.", exception);
        }
        if (workerThread != null) {
            try {
                workerThread.join(2000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    public void runStartupReconcile() {
        try {
            reconcileService.reconcileExistingFeedback(workspaceRoot);
        } catch (RuntimeException exception) {
            System.err.println("Feedback reconcile failed: " + exception);
        }
    }

    private void runLoop() {
        runStartupReconcile();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                processOnce();
            } catch (Throwable throwable) {
                System.err.println("Feedback sync loop error: " + throwable);
            }
        }
    }

    private void recordPendingEvent(Path path) {
        SyncStateRecord existingRecord = syncStateRepository.findByLocalPath(path).orElse(null);
        syncStateRepository.save(new SyncStateRecord(
            path,
            existingRecord == null ? null : existingRecord.remoteId(),
            existingRecord == null ? null : existingRecord.sha256(),
            SyncStatus.PENDING,
            existingRecord == null ? 0L : existingRecord.lastSynced()
        ));
    }

    private boolean isInstructorFeedbackPath(Path path) {
        Optional<CoursePath.ParsedLocation> parsed = CoursePath.parseFromWorkspace(workspaceRoot, path);
        return parsed.filter(location -> location.leaf() == Leaf.FEEDBACK && location.studentId().isPresent()).isPresent();
    }

    private boolean isIgnoredFeedbackFile(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return true;
        }
        String fileName = name.toString();
        return fileName.startsWith(".")
            || fileName.endsWith("~")
            || fileName.endsWith(".swp")
            || fileName.endsWith(".tmp")
            || fileName.endsWith(".crdownload")
            || fileName.endsWith(".part")
            || "desktop.ini".equalsIgnoreCase(fileName);
    }
}
