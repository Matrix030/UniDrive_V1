package edu.nyu.unidrive.client.net;

import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface AssignmentApiClient {

    List<AssignmentSummaryResponse> listAssignments(String term, String courseSlug) throws IOException;

    DownloadedFile downloadAssignment(String assignmentId, String fileName) throws IOException;

    AssignmentSummaryResponse publishAssignment(CoursePath coursePath, String title, String deadline, Path file) throws IOException;

    void deleteAssignment(String assignmentId, String fileName) throws IOException;
}
