package edu.nyu.unidrive.client.storage;

import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry.Course;
import edu.nyu.unidrive.common.workspace.WorkspaceRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceLayout {

    private WorkspaceLayout() {
    }

    public static void createTermAndCourses(Path rootDirectory, MockCourseRegistry registry) {
        Path termRoot = rootDirectory.resolve(registry.currentTerm());
        try {
            Files.createDirectories(termRoot);
            for (Course course : registry.courses()) {
                Files.createDirectories(termRoot.resolve(course.slug()));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create term/course directories under " + rootDirectory, exception);
        }
    }

    public static AssignmentSlot ensureAssignmentSlot(Path rootDirectory, CoursePath coursePath, WorkspaceRole role) {
        Path publishDir = coursePath.publishDirIn(rootDirectory, role);
        Path submissionsDir = coursePath.submissionsDirIn(rootDirectory, role);
        try {
            Files.createDirectories(publishDir);
            Files.createDirectories(submissionsDir);
            if (role == WorkspaceRole.STUDENT) {
                Files.createDirectories(coursePath.feedbackDirIn(rootDirectory));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to materialize assignment slot for " + coursePath, exception);
        }
        return new AssignmentSlot(coursePath, publishDir, submissionsDir);
    }
}
