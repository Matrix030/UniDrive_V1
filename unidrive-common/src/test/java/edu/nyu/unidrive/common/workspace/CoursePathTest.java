package edu.nyu.unidrive.common.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.nyu.unidrive.common.workspace.CoursePath.Leaf;
import edu.nyu.unidrive.common.workspace.CoursePath.ParsedLocation;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoursePathTest {

    private static final Path ROOT = Path.of("/tmp/unidrive").toAbsolutePath();

    @Test
    void toRelativePathProducesTermSlashCourseSlashAssignment() {
        CoursePath coursePath = new CoursePath("fall2026", "daa", "hw1");
        assertEquals(Path.of("fall2026", "daa", "hw1"), coursePath.toRelativePath());
    }

    @Test
    void instructorPublishAndSubmissionsDirsResolveBeneathRoot() {
        CoursePath coursePath = new CoursePath("fall2026", "daa", "hw1");
        assertEquals(ROOT.resolve("fall2026/daa/hw1/publish"), coursePath.publishDirIn(ROOT, WorkspaceRole.INSTRUCTOR));
        assertEquals(ROOT.resolve("fall2026/daa/hw1/submissions"), coursePath.submissionsDirIn(ROOT, WorkspaceRole.INSTRUCTOR));
    }

    @Test
    void studentFilesAndSubmissionDirsResolveBeneathRoot() {
        CoursePath coursePath = new CoursePath("fall2026", "daa", "hw1");
        assertEquals(ROOT.resolve("fall2026/daa/hw1/files"), coursePath.publishDirIn(ROOT, WorkspaceRole.STUDENT));
        assertEquals(ROOT.resolve("fall2026/daa/hw1/submission"), coursePath.submissionsDirIn(ROOT, WorkspaceRole.STUDENT));
        assertEquals(ROOT.resolve("fall2026/daa/hw1/feedback"), coursePath.feedbackDirIn(ROOT));
    }

    @Test
    void parseRecognizesStudentFilesDir() {
        Path file = ROOT.resolve("fall2026/daa/hw1/files/spec.pdf");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(Leaf.PUBLISH, parsed.leaf());
        assertEquals(Path.of("spec.pdf"), parsed.relativeFile());
    }

    @Test
    void parseRecognizesStudentSubmissionDir() {
        Path file = ROOT.resolve("fall2026/daa/hw1/submission/solution.pdf");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(Leaf.SUBMISSIONS, parsed.leaf());
        assertEquals(Path.of("solution.pdf"), parsed.relativeFile());
    }

    @Test
    void parseRecognizesPublishedAssignmentFile() {
        Path file = ROOT.resolve("fall2026/daa/hw1/publish/spec.pdf");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(new CoursePath("fall2026", "daa", "hw1"), parsed.coursePath());
        assertEquals(Leaf.PUBLISH, parsed.leaf());
        assertEquals(Optional.empty(), parsed.studentId());
        assertEquals(Path.of("spec.pdf"), parsed.relativeFile());
    }

    @Test
    void parseRecognizesStudentSubmissionWithoutStudentSubfolder() {
        Path file = ROOT.resolve("fall2026/daa/hw1/submissions/solution.pdf");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(Leaf.SUBMISSIONS, parsed.leaf());
        assertEquals(Optional.empty(), parsed.studentId());
        assertEquals(Path.of("solution.pdf"), parsed.relativeFile());
    }

    @Test
    void parseRecognizesInstructorSubmissionWithStudentSubfolder() {
        Path file = ROOT.resolve("fall2026/daa/hw1/submissions/student_rvg9395/solution.pdf");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(Leaf.SUBMISSIONS, parsed.leaf());
        assertEquals(Optional.of("rvg9395"), parsed.studentId());
        assertEquals(Path.of("solution.pdf"), parsed.relativeFile());
    }

    @Test
    void parseRecognizesFeedbackDir() {
        Path file = ROOT.resolve("fall2026/daa/hw1/feedback/comments.txt");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(Leaf.FEEDBACK, parsed.leaf());
        assertEquals(Optional.empty(), parsed.studentId());
        assertEquals(Path.of("comments.txt"), parsed.relativeFile());
    }

    @Test
    void parseRecognizesInstructorFeedbackUnderStudentSubmissionFolder() {
        Path file = ROOT.resolve("fall2026/daa/hw1/submissions/student_rvg9395/feedback/comments.txt");
        ParsedLocation parsed = CoursePath.parseFromWorkspace(ROOT, file).orElseThrow();
        assertEquals(Leaf.FEEDBACK, parsed.leaf());
        assertEquals(Optional.of("rvg9395"), parsed.studentId());
        assertEquals(Path.of("comments.txt"), parsed.relativeFile());
    }

    @Test
    void parseReturnsEmptyForFilesOutsideWorkspace() {
        Path file = Path.of("/tmp/elsewhere/foo.txt").toAbsolutePath();
        assertTrue(CoursePath.parseFromWorkspace(ROOT, file).isEmpty());
    }

    @Test
    void parseReturnsEmptyWhenLeafIsUnknown() {
        Path file = ROOT.resolve("fall2026/daa/hw1/unknown/spec.pdf");
        assertTrue(CoursePath.parseFromWorkspace(ROOT, file).isEmpty());
    }

    @Test
    void parseReturnsEmptyWhenPathIsTooShallow() {
        Path file = ROOT.resolve("fall2026/daa/hw1/publish");
        assertTrue(CoursePath.parseFromWorkspace(ROOT, file).isEmpty());
    }

    @Test
    void parseReturnsEmptyWhenStudentFolderHasNoFile() {
        Path file = ROOT.resolve("fall2026/daa/hw1/submissions/student_rvg9395");
        assertTrue(CoursePath.parseFromWorkspace(ROOT, file).isEmpty());
    }

    @Test
    void blankFieldsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CoursePath("", "daa", "hw1"));
        assertThrows(IllegalArgumentException.class, () -> new CoursePath("fall2026", " ", "hw1"));
        assertThrows(IllegalArgumentException.class, () -> new CoursePath("fall2026", "daa", null));
        assertFalse(false);
    }
}
