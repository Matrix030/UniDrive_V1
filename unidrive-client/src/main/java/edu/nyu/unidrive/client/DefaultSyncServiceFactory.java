package edu.nyu.unidrive.client;

import edu.nyu.unidrive.client.net.RestAssignmentApiClient;
import edu.nyu.unidrive.client.net.RestFeedbackApiClient;
import edu.nyu.unidrive.client.net.RestSubmissionApiClient;
import edu.nyu.unidrive.client.storage.ClientWorkspace;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.client.sync.AssignmentSyncService;
import edu.nyu.unidrive.client.sync.FeedbackSyncService;
import edu.nyu.unidrive.client.sync.ReceivedReconcileService;
import edu.nyu.unidrive.client.sync.RemotePollingService;
import edu.nyu.unidrive.client.sync.SubmissionDirectoryWatcher;
import edu.nyu.unidrive.client.sync.SubmissionReconcileService;
import edu.nyu.unidrive.client.sync.SubmissionSyncStateService;
import edu.nyu.unidrive.client.sync.SubmissionUploadService;
import edu.nyu.unidrive.client.sync.SyncService;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.client.RestTemplate;

public final class DefaultSyncServiceFactory implements SyncServiceFactory {

    @Override
    public SyncServiceHandle create(ClientWorkspace workspace, String studentId, String baseUrl) {
        try {
            SyncStateRepository syncStateRepository = new SyncStateRepository(workspace.databasePath());
            ReceivedStateRepository receivedStateRepository = new ReceivedStateRepository(workspace.databasePath());
            SubmissionDirectoryWatcher watcher = new SubmissionDirectoryWatcher(workspace.rootDirectory());
            SubmissionSyncStateService syncStateService = new SubmissionSyncStateService(syncStateRepository);
            SubmissionUploadService uploadService = new SubmissionUploadService(
                syncStateRepository,
                new RestSubmissionApiClient(baseUrl, new RestTemplate()),
                workspace.rootDirectory()
            );
            SubmissionReconcileService reconcileService = new SubmissionReconcileService(syncStateRepository);
            AssignmentSyncService assignmentSyncService = new AssignmentSyncService(
                new RestAssignmentApiClient(baseUrl, new RestTemplate()),
                receivedStateRepository
            );
            FeedbackSyncService feedbackSyncService = new FeedbackSyncService(
                new RestFeedbackApiClient(baseUrl, new RestTemplate()),
                receivedStateRepository
            );
            ReceivedReconcileService receivedReconcileService = new ReceivedReconcileService(receivedStateRepository);

            SyncService submissionSyncService = new SyncService(
                watcher,
                syncStateService,
                uploadService,
                reconcileService,
                syncStateRepository,
                workspace.rootDirectory(),
                studentId,
                Duration.ofMillis(250)
            );
            RemotePollingService remotePollingService = new RemotePollingService(
                assignmentSyncService,
                feedbackSyncService,
                receivedReconcileService,
                workspace.rootDirectory(),
                studentId,
                new MockCourseRegistry(),
                Duration.ofSeconds(2)
            );

            return new CompositeSyncServiceHandle(submissionSyncService, remotePollingService);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create client sync service.", exception);
        }
    }
}
