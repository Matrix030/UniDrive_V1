package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.SyncServiceHandle;
import edu.nyu.unidrive.client.storage.SyncStateRecord;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import edu.nyu.unidrive.common.workspace.CoursePath.ParsedLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

public final class PublishSyncService implements SyncServiceHandle {

    private final PublishDirectoryWatcher watcher;
    private final PublishUploadService uploadService;
    private final SyncStateRepository syncStateRepository;
    private final Path workspaceRoot;
    private final Duration pollTimeout;
    private Thread workerThread;

    private static final int RECONCILE_SWEEP_EVERY_N_ITERATIONS = 20;
    private int loopIteration = 0;

    public PublishSyncService(
        PublishDirectoryWatcher watcher,
        PublishUploadService uploadService,
        SyncStateRepository syncStateRepository,
        Path workspaceRoot,
        Duration pollTimeout
    ) {
        this.watcher = watcher;
        this.uploadService = uploadService;
        this.syncStateRepository = syncStateRepository;
        this.workspaceRoot = workspaceRoot;
        this.pollTimeout = pollTimeout;
    }

    @Override
    public synchronized void start() {
        if (workerThread != null) {
            return;
        }
        workerThread = new Thread(this::runLoop, "unidrive-publish-sync");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void processOnce() {
        for (SubmissionFileEvent event : watcher.pollEvents(pollTimeout)) {
            if (event.type() == SubmissionFileEventType.OVERFLOW) {
                reconcilePublishSubtree(event.path());
                continue;
            }
            if (event.type() == SubmissionFileEventType.DELETED) {
                tryDelete(event.path());
            } else {
                tryPublish(event.path());
            }
        }

        if (loopIteration++ % RECONCILE_SWEEP_EVERY_N_ITERATIONS != 0) {
            return;
        }
        reconcileDeletedPublishFiles();
    }

    private void reconcilePublishSubtree(Path subtree) {
        if (!Files.isDirectory(subtree)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(subtree)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> CoursePath.parseFromWorkspace(workspaceRoot, path)
                    .map(parsed -> parsed.leaf() == Leaf.PUBLISH)
                    .orElse(false))
                .forEach(this::tryPublish);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reconcile publish subtree: " + subtree, exception);
        }
    }

    private void tryPublish(Path path) {
        if (isIgnoredPublishFile(path)) {
            return;
        }
        if (!Files.isRegularFile(path)) {
            return;
        }

        Optional<ParsedLocation> parsed = CoursePath.parseFromWorkspace(workspaceRoot, path);
        if (parsed.isEmpty() || parsed.get().leaf() != Leaf.PUBLISH) {
            return;
        }
        CoursePath coursePath = parsed.get().coursePath();

        if (isAlreadySynced(path)) {
            return;
        }

        syncStateRepository.save(new SyncStateRecord(path, null, null, SyncStatus.PENDING, 0L));
        try {
            var response = uploadService.publish(coursePath, path);
            syncStateRepository.save(new SyncStateRecord(
                path,
                response.getAssignmentId(),
                response.getSha256(),
                SyncStatus.SYNCED,
                System.currentTimeMillis()
            ));
        } catch (Exception exception) {
            System.err.println("Publish failed for " + path + ": " + exception);
            syncStateRepository.save(new SyncStateRecord(path, null, null, SyncStatus.FAILED, 0L));
        }
    }

    private void tryDelete(Path path) {
        SyncStateRecord existing = syncStateRepository.findByLocalPath(path).orElse(null);
        if (existing == null || existing.remoteId() == null || existing.remoteId().isBlank()) {
            syncStateRepository.deleteByLocalPath(path);
            return;
        }
        Optional<ParsedLocation> parsed = CoursePath.parseFromWorkspace(workspaceRoot, path);
        if (parsed.isEmpty() || parsed.get().leaf() != Leaf.PUBLISH) {
            syncStateRepository.deleteByLocalPath(path);
            return;
        }

        try {
            uploadService.delete(parsed.get().coursePath(), path);
            syncStateRepository.deleteByLocalPath(path);
        } catch (Exception exception) {
            System.err.println("Publish delete failed for " + path + ": " + exception);
            syncStateRepository.save(new SyncStateRecord(path, null, null, SyncStatus.FAILED, 0L));
        }
    }

    private void reconcileExistingPublishFiles() {
        if (!Files.isDirectory(workspaceRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> CoursePath.parseFromWorkspace(workspaceRoot, path)
                    .map(parsed -> parsed.leaf() == Leaf.PUBLISH)
                    .orElse(false))
                .forEach(this::tryPublish);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reconcile publish files in workspace: " + workspaceRoot, exception);
        }
    }

    private void reconcileDeletedPublishFiles() {
        for (SyncStateRecord record : syncStateRepository.findAll()) {
            if (Files.exists(record.localPath())) {
                continue;
            }
            CoursePath.parseFromWorkspace(workspaceRoot, record.localPath())
                .filter(parsed -> parsed.leaf() == Leaf.PUBLISH)
                .ifPresent(parsed -> tryDelete(record.localPath()));
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
            throw new IllegalStateException("Failed to close publish watcher.", exception);
        }
        if (workerThread != null) {
            try {
                workerThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    public void runStartupReconcile() {
        try {
            reconcileDeletedPublishFiles();
            reconcileExistingPublishFiles();
        } catch (RuntimeException exception) {
            System.err.println("Publish reconcile failed: " + exception);
        }
    }

    private void runLoop() {
        runStartupReconcile();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                processOnce();
            } catch (Throwable throwable) {
                System.err.println("Publish sync loop error: " + throwable);
            }
        }
    }

    private boolean isIgnoredPublishFile(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return true;
        }
        String fileName = name.toString();
        if (fileName.startsWith(".")) {
            return true;
        }
        if (fileName.endsWith("~") || fileName.endsWith(".swp") || fileName.endsWith(".tmp") || fileName.endsWith(".crdownload") || fileName.endsWith(".part")) {
            return true;
        }
        if ("desktop.ini".equalsIgnoreCase(fileName) || "Untitled.md".equalsIgnoreCase(fileName) || "Untitled".equalsIgnoreCase(fileName)) {
            return true;
        }
        try {
            if (Files.size(path) == 0L) {
                return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private boolean isAlreadySynced(Path path) {
        return syncStateRepository.findByLocalPath(path)
            .filter(existing -> existing.status() == SyncStatus.SYNCED)
            .filter(existing -> existing.sha256() != null)
            .map(existing -> {
                try {
                    return existing.sha256().equals(FileHasher.sha256Hex(path));
                } catch (IOException ignored) {
                    return false;
                }
            })
            .orElse(false);
    }
}
