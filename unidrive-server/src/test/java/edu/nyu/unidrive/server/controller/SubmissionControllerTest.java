package edu.nyu.unidrive.server.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.nyu.unidrive.common.util.FileHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "unidrive.storage.root=target/test-storage",
    "spring.datasource.url=jdbc:sqlite:target/test-submissions.db"
})
class SubmissionControllerTest {

    private static final Path STORAGE_ROOT = Path.of("target/test-storage");
    private static final String TERM = "fall2026";
    private static final String COURSE = "daa";
    private static final long FUTURE_DEADLINE = 4102444740000L;
    private static final long PAST_DEADLINE = 946684800000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearStorageRoot() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_ROOT);
        Files.createDirectories(STORAGE_ROOT);
        jdbcTemplate.execute("DELETE FROM submissions");
        jdbcTemplate.execute("DELETE FROM assignments");
    }

    @Test
    void uploadSubmissionStoresFileAndReturnsReceiptWhenHashMatches() throws Exception {
        byte[] content = "public class Hello { }".getBytes();
        ensureAssignment(TERM, COURSE, "assignment-1", FUTURE_DEADLINE);
        String sha256 = FileHasher.sha256Hex(content);
        Integer submissionCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
        int beforeCount = submissionCountBefore == null ? 0 : submissionCountBefore;
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "Hello.java",
            MediaType.TEXT_PLAIN_VALUE,
            content
        );

        mockMvc.perform(
                multipart("/api/v1/submissions/{term}/{course}/{assignmentId}", TERM, COURSE, "assignment-1")
                    .file(file)
                    .param("studentId", "rvg9395")
                    .header("X-File-Sha256", sha256)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Submission uploaded successfully."))
            .andExpect(jsonPath("$.data.submissionId").value(matchesPattern("[0-9a-f\\-]{36}")))
            .andExpect(jsonPath("$.data.term").value(TERM))
            .andExpect(jsonPath("$.data.course").value(COURSE))
            .andExpect(jsonPath("$.data.assignmentId").value("assignment-1"))
            .andExpect(jsonPath("$.data.studentId").value("rvg9395"))
            .andExpect(jsonPath("$.data.fileName").value("Hello.java"))
            .andExpect(jsonPath("$.data.sha256").value(sha256));

        try (Stream<Path> storedFiles = Files.walk(STORAGE_ROOT)) {
            long fileCount = storedFiles.filter(Files::isRegularFile).count();
            org.junit.jupiter.api.Assertions.assertEquals(1, fileCount);
        }

        Integer submissionCountAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
        int afterCount = submissionCountAfter == null ? 0 : submissionCountAfter;
        org.junit.jupiter.api.Assertions.assertEquals(beforeCount + 1, afterCount);

        Map<String, Object> savedSubmission = jdbcTemplate.queryForMap(
            "SELECT term, course, assignment_id, student_id, hash, status FROM submissions WHERE hash = ?",
            sha256
        );
        org.junit.jupiter.api.Assertions.assertEquals(TERM, savedSubmission.get("term"));
        org.junit.jupiter.api.Assertions.assertEquals(COURSE, savedSubmission.get("course"));
        org.junit.jupiter.api.Assertions.assertEquals("assignment-1", savedSubmission.get("assignment_id"));
        org.junit.jupiter.api.Assertions.assertEquals("rvg9395", savedSubmission.get("student_id"));
        org.junit.jupiter.api.Assertions.assertEquals(sha256, savedSubmission.get("hash"));
        org.junit.jupiter.api.Assertions.assertEquals("SYNCED", savedSubmission.get("status"));

        Path studentDir = STORAGE_ROOT.resolve(TERM).resolve(COURSE).resolve("assignment-1").resolve("submissions").resolve("student_rvg9395");
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(studentDir), "expected student dir at " + studentDir);
    }

    @Test
    void uploadSubmissionRejectsHashMismatchAndDoesNotStoreFile() throws Exception {
        byte[] content = "class BrokenHash { }".getBytes();
        ensureAssignment(TERM, COURSE, "assignment-1", FUTURE_DEADLINE);
        Integer submissionCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
        int beforeCount = submissionCountBefore == null ? 0 : submissionCountBefore;
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "BrokenHash.java",
            MediaType.TEXT_PLAIN_VALUE,
            content
        );

        mockMvc.perform(
                multipart("/api/v1/submissions/{term}/{course}/{assignmentId}", TERM, COURSE, "assignment-1")
                    .file(file)
                    .param("studentId", "rvg9395")
                    .header("X-File-Sha256", "not-the-real-hash")
            )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Uploaded file hash did not match the provided SHA-256."));

        try (Stream<Path> storedFiles = Files.walk(STORAGE_ROOT)) {
            long fileCount = storedFiles.filter(Files::isRegularFile).count();
            org.junit.jupiter.api.Assertions.assertEquals(0, fileCount);
        }

        Integer submissionCountAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class);
        int afterCount = submissionCountAfter == null ? 0 : submissionCountAfter;
        org.junit.jupiter.api.Assertions.assertEquals(beforeCount, afterCount);
    }

    @Test
    void uploadSubmissionRejectsMissingAssignment() throws Exception {
        byte[] content = "class MissingAssignment { }".getBytes();
        String sha256 = FileHasher.sha256Hex(content);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "MissingAssignment.java",
            MediaType.TEXT_PLAIN_VALUE,
            content
        );

        mockMvc.perform(
                multipart("/api/v1/submissions/{term}/{course}/{assignmentId}", TERM, COURSE, "missing-assignment")
                    .file(file)
                    .param("studentId", "rvg9395")
                    .header("X-File-Sha256", sha256)
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Assignment was not found."));
    }

    @Test
    void uploadSubmissionRejectsPastDeadline() throws Exception {
        byte[] content = "class Late { }".getBytes();
        ensureAssignment(TERM, COURSE, "assignment-1", PAST_DEADLINE);
        String sha256 = FileHasher.sha256Hex(content);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "Late.java",
            MediaType.TEXT_PLAIN_VALUE,
            content
        );

        mockMvc.perform(
                multipart("/api/v1/submissions/{term}/{course}/{assignmentId}", TERM, COURSE, "assignment-1")
                    .file(file)
                    .param("studentId", "rvg9395")
                    .header("X-File-Sha256", sha256)
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Assignment deadline has passed."));
    }

    @Test
    void listSubmissionsReturnsOnlyRowsForRequestedAssignment() throws Exception {
        uploadSubmission(TERM, COURSE, "assignment-1", "rvg9395", "First.java", "class First { }".getBytes());
        uploadSubmission(TERM, COURSE, "assignment-2", "ow2130", "Second.java", "class Second { }".getBytes());

        mockMvc.perform(
                get("/api/v1/submissions")
                    .param("term", TERM)
                    .param("course", COURSE)
                    .param("assignmentId", "assignment-1")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Submissions retrieved successfully."))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].term").value(TERM))
            .andExpect(jsonPath("$.data[0].course").value(COURSE))
            .andExpect(jsonPath("$.data[0].assignmentId").value("assignment-1"))
            .andExpect(jsonPath("$.data[0].studentId").value("rvg9395"))
            .andExpect(jsonPath("$.data[0].fileName").value("First.java"))
            .andExpect(jsonPath("$.data[0].status").value("SYNCED"));
    }

    @Test
    void listSubmissionsCanFilterByAssignmentAndStudent() throws Exception {
        uploadSubmission(TERM, COURSE, "assignment-1", "rvg9395", "Alpha.java", "class Alpha { }".getBytes());
        uploadSubmission(TERM, COURSE, "assignment-1", "ow2130", "Beta.java", "class Beta { }".getBytes());

        mockMvc.perform(
                get("/api/v1/submissions")
                    .param("term", TERM)
                    .param("course", COURSE)
                    .param("assignmentId", "assignment-1")
                    .param("studentId", "rvg9395")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Submissions retrieved successfully."))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].assignmentId").value("assignment-1"))
            .andExpect(jsonPath("$.data[0].studentId").value("rvg9395"))
            .andExpect(jsonPath("$.data[0].fileName").value("Alpha.java"))
            .andExpect(jsonPath("$.data[0].status").value("SYNCED"));
    }

    @Test
    void downloadSubmissionReturnsStoredFileContents() throws Exception {
        byte[] contentBytes = "class DownloadMe { }".getBytes();
        uploadSubmission(TERM, COURSE, "assignment-1", "rvg9395", "DownloadMe.java", contentBytes);

        String submissionId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE assignment_id = ? AND student_id = ? ORDER BY submitted_at DESC LIMIT 1",
            String.class,
            "assignment-1",
            "rvg9395"
        );

        mockMvc.perform(get("/api/v1/submissions/{submissionId}/download", submissionId))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"DownloadMe.java\"")))
            .andExpect(content().bytes(contentBytes));
    }

    @Test
    void downloadSubmissionReturns404WhenSubmissionDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/submissions/{submissionId}/download", "missing-submission-id"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteSubmissionRejectsPastDeadline() throws Exception {
        uploadSubmission(TERM, COURSE, "assignment-1", "rvg9395", "Solution.java", "class Solution { }".getBytes());
        String submissionId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE assignment_id = ? AND student_id = ? ORDER BY submitted_at DESC LIMIT 1",
            String.class,
            "assignment-1",
            "rvg9395"
        );
        jdbcTemplate.update("UPDATE assignments SET deadline = ? WHERE id = ?", PAST_DEADLINE, "assignment-1");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/submissions/{submissionId}", submissionId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Assignment deadline has passed."));

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class));
    }

    private void uploadSubmission(
        String term,
        String course,
        String assignmentId,
        String studentId,
        String fileName,
        byte[] content
    ) throws Exception {
        ensureAssignment(term, course, assignmentId, FUTURE_DEADLINE);
        String sha256 = FileHasher.sha256Hex(content);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            fileName,
            MediaType.TEXT_PLAIN_VALUE,
            content
        );

        mockMvc.perform(
                multipart("/api/v1/submissions/{term}/{course}/{assignmentId}", term, course, assignmentId)
                    .file(file)
                    .param("studentId", studentId)
                    .header("X-File-Sha256", sha256)
            )
            .andExpect(status().isOk());
    }

    private void ensureAssignment(String term, String course, String assignmentId, long deadline) {
        jdbcTemplate.update(
            """
            INSERT OR IGNORE INTO assignments (id, file_name, term, course, title, deadline, published_at, file_path, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            assignmentId,
            "spec.md",
            term,
            course,
            "Assignment",
            deadline,
            System.currentTimeMillis(),
            STORAGE_ROOT.resolve(term).resolve(course).resolve(assignmentId).resolve("publish/spec.md").toString(),
            "assignment-hash"
        );
    }
}
