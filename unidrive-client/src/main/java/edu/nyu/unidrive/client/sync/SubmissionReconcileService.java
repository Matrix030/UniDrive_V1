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

public class SubmissionReconcileService {

    private final SyncStateRepository syncStateRepository;

    public SubmissionReconcileService(SyncStateRepository syncStateRepository) {
        this.syncStateRepository = syncStateRepository;
    }

    public void reconcileExistingSubmissions(Path workspaceRoot) {
        reconcileSubtree(workspaceRoot, workspaceRoot);
    }

    public void reconcileSubtree(Path workspaceRoot, Path subtree) {
        if (!Files.isDirectory(subtree)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(subtree)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> CoursePath.parseFromWorkspace(workspaceRoot, path)
                    .map(parsed -> parsed.leaf() == Leaf.SUBMISSIONS)
                    .orElse(false))
                .forEach(path -> {
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
                });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reconcile submissions in subtree: " + subtree, e);
        }
    }
}
