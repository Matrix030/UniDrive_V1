package edu.nyu.unidrive.client.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.common.workspace.CoursePath;
import edu.nyu.unidrive.common.workspace.MockCourseRegistry;
import edu.nyu.unidrive.common.workspace.WorkspaceRole;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLayoutTest {

    @Test
    void createTermAndCoursesMaterializesEachCourseSlugUnderCurrentTerm(@TempDir Path tempDir) {
        WorkspaceLayout.createTermAndCourses(tempDir, new MockCourseRegistry());

        Path termRoot = tempDir.resolve("fall2026");
        assertTrue(Files.isDirectory(termRoot));
        assertTrue(Files.isDirectory(termRoot.resolve("daa")));
        assertTrue(Files.isDirectory(termRoot.resolve("java")));
        assertTrue(Files.isDirectory(termRoot.resolve("bda")));
    }

    @Test
    void ensureAssignmentSlotInstructorCreatesPublishAndSubmissions(@TempDir Path tempDir) {
        CoursePath coursePath = new CoursePath("fall2026", "daa", "hw1");

        AssignmentSlot slot = WorkspaceLayout.ensureAssignmentSlot(tempDir, coursePath, WorkspaceRole.INSTRUCTOR);

        assertEquals(coursePath, slot.coursePath());
        assertEquals(tempDir.resolve("fall2026/daa/hw1/publish"), slot.publishDir());
        assertEquals(tempDir.resolve("fall2026/daa/hw1/submissions"), slot.submissionsDir());
        assertTrue(Files.isDirectory(slot.publishDir()));
        assertTrue(Files.isDirectory(slot.submissionsDir()));
    }

    @Test
    void ensureAssignmentSlotStudentCreatesFilesAndSubmission(@TempDir Path tempDir) {
        CoursePath coursePath = new CoursePath("fall2026", "daa", "hw1");

        AssignmentSlot slot = WorkspaceLayout.ensureAssignmentSlot(tempDir, coursePath, WorkspaceRole.STUDENT);

        assertEquals(tempDir.resolve("fall2026/daa/hw1/files"), slot.publishDir());
        assertEquals(tempDir.resolve("fall2026/daa/hw1/submission"), slot.submissionsDir());
        assertTrue(Files.isDirectory(slot.publishDir()));
        assertTrue(Files.isDirectory(slot.submissionsDir()));
        assertTrue(Files.isDirectory(tempDir.resolve("fall2026/daa/hw1/feedback")));
    }

    @Test
    void ensureAssignmentSlotIsIdempotent(@TempDir Path tempDir) {
        CoursePath coursePath = new CoursePath("fall2026", "daa", "hw1");
        AssignmentSlot first = WorkspaceLayout.ensureAssignmentSlot(tempDir, coursePath, WorkspaceRole.INSTRUCTOR);
        AssignmentSlot second = WorkspaceLayout.ensureAssignmentSlot(tempDir, coursePath, WorkspaceRole.INSTRUCTOR);

        assertEquals(first, second);
    }
}
