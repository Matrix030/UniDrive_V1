package edu.nyu.unidrive.client.sync;

import edu.nyu.unidrive.client.net.AssignmentApiClient;
import edu.nyu.unidrive.client.storage.AssignmentDeadlineStore;
import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.nio.file.Path;

public final class PublishUploadService {

    private final AssignmentApiClient assignmentApiClient;
    private final Path workspaceRoot;

    public PublishUploadService(AssignmentApiClient assignmentApiClient, Path workspaceRoot) {
        this.assignmentApiClient = assignmentApiClient;
        this.workspaceRoot = workspaceRoot;
    }

    public AssignmentSummaryResponse publish(CoursePath coursePath, Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String title = stripExtension(fileName);
        String deadline = AssignmentDeadlineStore.readDeadline(workspaceRoot, coursePath)
            .orElseThrow(() -> new IOException("Create the assignment slot with a deadline before publishing files."));
        return assignmentApiClient.publishAssignment(coursePath, title, deadline, file);
    }

    public void delete(CoursePath coursePath, Path file) throws IOException {
        assignmentApiClient.deleteAssignment(coursePath.assignmentId(), file.getFileName().toString());
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }
}
