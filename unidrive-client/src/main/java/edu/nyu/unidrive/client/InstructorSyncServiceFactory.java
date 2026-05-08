package edu.nyu.unidrive.client;

import edu.nyu.unidrive.client.net.RestAssignmentApiClient;
import edu.nyu.unidrive.client.net.RestFeedbackApiClient;
import edu.nyu.unidrive.client.net.RestSubmissionApiClient;
import edu.nyu.unidrive.client.storage.InstructorWorkspace;
import edu.nyu.unidrive.client.storage.ReceivedStateRepository;
import edu.nyu.unidrive.client.storage.SyncStateRepository;
import edu.nyu.unidrive.client.sync.FeedbackDirectoryWatcher;
import edu.nyu.unidrive.client.sync.FeedbackReconcileService;
import edu.nyu.unidrive.client.sync.FeedbackUploadService;
import edu.nyu.unidrive.client.sync.InstructorFeedbackSyncService;
import edu.nyu.unidrive.client.sync.InstructorSubmissionPollingService;
import edu.nyu.unidrive.client.sync.PublishDirectoryWatcher;
import edu.nyu.unidrive.client.sync.PublishSyncService;
import edu.nyu.unidrive.client.sync.PublishUploadService;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.client.RestTemplate;

public final class InstructorSyncServiceFactory {

    public SyncServiceHandle create(InstructorWorkspace workspace, String baseUrl) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            PublishDirectoryWatcher publishWatcher = new PublishDirectoryWatcher(workspace.rootDirectory());
            PublishUploadService publishUploadService = new PublishUploadService(
                new RestAssignmentApiClient(baseUrl, restTemplate)
            );
            SyncStateRepository syncStateRepository = new SyncStateRepository(workspace.databasePath());
            ReceivedStateRepository receivedStateRepository = new ReceivedStateRepository(workspace.databasePath());
            PublishSyncService publishSyncService = new PublishSyncService(
                publishWatcher,
                publishUploadService,
                syncStateRepository,
                workspace.rootDirectory(),
                Duration.ofMillis(250)
            );

            InstructorSubmissionPollingService submissionPolling = new InstructorSubmissionPollingService(
                new RestSubmissionApiClient(baseUrl, restTemplate),
                workspace.rootDirectory(),
                new MockCourseRegistry(),
                receivedStateRepository,
                Duration.ofSeconds(2)
            );

            FeedbackUploadService feedbackUploadService = new FeedbackUploadService(
                syncStateRepository,
                new RestFeedbackApiClient(baseUrl, restTemplate),
                new RestSubmissionApiClient(baseUrl, restTemplate),
                workspace.rootDirectory()
            );
            InstructorFeedbackSyncService feedbackSyncService = new InstructorFeedbackSyncService(
                new FeedbackDirectoryWatcher(workspace.rootDirectory()),
                feedbackUploadService,
                new FeedbackReconcileService(syncStateRepository),
                syncStateRepository,
                workspace.rootDirectory(),
                Duration.ofMillis(250)
            );

            return new CompositeSyncServiceHandle(publishSyncService, submissionPolling, feedbackSyncService);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create instructor sync service.", exception);
        }
    }
}
