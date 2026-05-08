package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.net.FeedbackApiClient;
import edu.nyu.unidrive.client.net.SubmissionApiClient;
import edu.nyu.unidrive.client.storage.SyncStateRecord;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.model.SyncStatus;
import edu.nyu.unidrive.common.util.FileHasher;
import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import edu.nyu.unidrive.common.workspace.CoursePath.ParsedLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FeedbackUploadService {

    private final SyncStateRepository syncStateRepository;
    private final FeedbackApiClient feedbackApiClient;
    private final SubmissionApiClient submissionApiClient;
    private final Path workspaceRoot;
    private final Map<CoursePath, List<SubmissionSummaryResponse>> submissionCache = new HashMap<>();

    public FeedbackUploadService(
        SyncStateRepository syncStateRepository,
        FeedbackApiClient feedbackApiClient,
        SubmissionApiClient submissionApiClient,
        Path workspaceRoot
    ) {
        this.syncStateRepository = syncStateRepository;
        this.feedbackApiClient = feedbackApiClient;
        this.submissionApiClient = submissionApiClient;
        this.workspaceRoot = workspaceRoot;
    }

    public SyncStatus uploadPendingFeedback(Path localPath) {
        Optional<ParsedLocation> parsed = CoursePath.parseFromWorkspace(workspaceRoot, localPath);
        if (parsed.isEmpty() || parsed.get().leaf() != Leaf.FEEDBACK || parsed.get().studentId().isEmpty()) {
            return SyncStatus.FAILED;
        }

        SyncStateRecord existingRecord = syncStateRepository.findByLocalPath(localPath).orElse(null);
        try {
            String sha256 = FileHasher.sha256Hex(localPath);
            if (existingRecord != null
                && existingRecord.remoteId() != null
                && existingRecord.sha256() != null
                && existingRecord.sha256().equals(sha256)
                && existingRecord.lastSynced() > 0L) {
                syncStateRepository.save(new SyncStateRecord(
                    localPath,
                    existingRecord.remoteId(),
                    sha256,
                    SyncStatus.SYNCED,
                    existingRecord.lastSynced()
                ));
                return SyncStatus.SYNCED;
            }

            syncStateRepository.save(new SyncStateRecord(
                localPath,
                existingRecord == null ? null : existingRecord.remoteId(),
                sha256,
                SyncStatus.UPLOADING,
                existingRecord == null ? 0L : existingRecord.lastSynced()
            ));

            String submissionId = latestSubmissionId(parsed.get().coursePath(), parsed.get().studentId().orElseThrow());
            var response = feedbackApiClient.uploadFeedback(submissionId, localPath);
            syncStateRepository.save(new SyncStateRecord(
                localPath,
                response.getFeedbackId(),
                response.getSha256(),
                SyncStatus.SYNCED,
                System.currentTimeMillis()
            ));
            return SyncStatus.SYNCED;
        } catch (IOException | RuntimeException exception) {
            saveFailure(localPath, existingRecord);
            return SyncStatus.FAILED;
        }
    }

    public void deleteFeedback(Path localPath) {
        SyncStateRecord existingRecord = syncStateRepository.findByLocalPath(localPath).orElse(null);
        if (existingRecord == null) {
            return;
        }
        try {
            if (existingRecord.remoteId() != null && !existingRecord.remoteId().isBlank()) {
                feedbackApiClient.deleteFeedback(existingRecord.remoteId());
            }
            syncStateRepository.deleteByLocalPath(localPath);
        } catch (IOException | RuntimeException exception) {
            syncStateRepository.save(new SyncStateRecord(
                localPath,
                existingRecord.remoteId(),
                existingRecord.sha256(),
                SyncStatus.FAILED,
                existingRecord.lastSynced()
            ));
        }
    }

    public void resetSubmissionCache() {
        submissionCache.clear();
    }

    private String latestSubmissionId(CoursePath coursePath, String studentId) throws IOException {
        List<SubmissionSummaryResponse> submissions = submissionCache.get(coursePath);
        if (submissions == null) {
            submissions = submissionApiClient.listSubmissions(coursePath);
            submissionCache.put(coursePath, submissions);
        }
        return submissions.stream()
            .filter(submission -> studentId.equals(submission.getStudentId()))
            .map(SubmissionSummaryResponse::getSubmissionId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No submission found for " + studentId + " in " + coursePath));
    }

    private void saveFailure(Path localPath, SyncStateRecord existingRecord) {
        String fallbackSha256 = null;
        try {
            fallbackSha256 = FileHasher.sha256Hex(localPath);
        } catch (IOException ignored) {
        }
        syncStateRepository.save(new SyncStateRecord(
            localPath,
            existingRecord == null ? null : existingRecord.remoteId(),
            fallbackSha256,
            SyncStatus.FAILED,
            existingRecord == null ? 0L : existingRecord.lastSynced()
        ));
    }
}
