package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.storage.SyncStateRecord;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class FeedbackReconcileService {

    private final SyncStateRepository syncStateRepository;

    public FeedbackReconcileService(SyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    public void reconcileExistingFeedback(Path workspaceRoot) {
        reconcileSubtree(workspaceRoot, workspaceRoot);
    }

    public void reconcileSubtree(Path workspaceRoot, Path subtree) {
        if (!Files.isDirectory(subtree)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(subtree)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> isInstructorFeedbackFile(workspaceRoot, path))
                .filter(path -> !isIgnoredFile(path))
                .forEach(path -> reconcileFile(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reconcile feedback in subtree: " + subtree, exception);
        }
    }

    private void reconcileFile(Path path) {
        SyncStateRecord existing = syncStateRepository.findByLocalPath(path).orElse(null);
        if (existing == null) {
            syncStateRepository.save(new SyncStateRecord(path, null, null, SyncStatus.PENDING, 0L));
            return;
        }
        try {
            String currentHash = FileHasher.sha256Hex(path);
            if (!currentHash.equals(existing.sha256())) {
                syncStateRepository.save(new SyncStateRecord(
                    path,
                    existing.remoteId(),
                    existing.sha256(),
                    SyncStatus.PENDING,
                    existing.lastSynced()
                ));
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isInstructorFeedbackFile(Path workspaceRoot, Path path) {
        return CoursePath.parseFromWorkspace(workspaceRoot, path)
            .map(parsed -> parsed.leaf() == Leaf.FEEDBACK && parsed.studentId().isPresent())
            .orElse(false);
    }

    private boolean isIgnoredFile(Path path) {
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
