package edu.nyu.unidrive.server.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "unidrive.storage.root=target/test-feedback-storage",
    "spring.datasource.url=jdbc:sqlite:target/test-feedback.db"
})
class FeedbackControllerTest {

    private static final Path STORAGE_ROOT = Path.of("target/test-feedback-storage");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearState() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_ROOT);
        Files.createDirectories(STORAGE_ROOT);
        jdbcTemplate.execute("DELETE FROM feedback");
        jdbcTemplate.execute("DELETE FROM submissions");
        jdbcTemplate.execute("DELETE FROM assignments");
    }

    @Test
    void uploadFeedbackStoresFileAndMetadata() throws Exception {
        String submissionId = createSubmission("assignment-1", "rvg9395", "Solution.java", "class Solution {}".getBytes());
        byte[] content = "feedback comments".getBytes();
        String sha256 = FileHasher.sha256Hex(content);
        MockMultipartFile file = new MockMultipartFile("file", "Feedback.txt", MediaType.TEXT_PLAIN_VALUE, content);

        mockMvc.perform(
                multipart("/api/v1/instructor/feedback/{submissionId}", submissionId)
                    .file(file)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Feedback uploaded successfully."))
            .andExpect(jsonPath("$.data.feedbackId").value(matchesPattern("[0-9a-f\\-]{36}")))
            .andExpect(jsonPath("$.data.submissionId").value(submissionId))
            .andExpect(jsonPath("$.data.term").value("fall2026"))
            .andExpect(jsonPath("$.data.course").value("daa"))
            .andExpect(jsonPath("$.data.assignmentId").value("assignment-1"))
            .andExpect(jsonPath("$.data.studentId").value("rvg9395"))
            .andExpect(jsonPath("$.data.fileName").value("Feedback.txt"))
            .andExpect(jsonPath("$.data.sha256").value(sha256));

        Map<String, Object> saved = jdbcTemplate.queryForMap(
            "SELECT submission_id, file_name, hash FROM feedback WHERE submission_id = ?",
            submissionId
        );
        org.junit.jupiter.api.Assertions.assertEquals(submissionId, saved.get("submission_id"));
        org.junit.jupiter.api.Assertions.assertEquals("Feedback.txt", saved.get("file_name"));
        org.junit.jupiter.api.Assertions.assertEquals(sha256, saved.get("hash"));

        try (Stream<Path> storedFiles = Files.walk(STORAGE_ROOT)) {
            org.junit.jupiter.api.Assertions.assertEquals(2, storedFiles.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void listFeedbackReturnsOnlyRowsForStudent() throws Exception {
        String submissionOne = createSubmission("assignment-1", "rvg9395", "One.java", "one".getBytes());
        String submissionTwo = createSubmission("assignment-1", "ow2130", "Two.java", "two".getBytes());
        uploadFeedback(submissionOne, "Feedback1.txt", "good".getBytes());
        uploadFeedback(submissionTwo, "Feedback2.txt", "bad".getBytes());

        mockMvc.perform(get("/api/v1/feedback").param("studentId", "rvg9395"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Feedback retrieved successfully."))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].term").value("fall2026"))
            .andExpect(jsonPath("$.data[0].course").value("daa"))
            .andExpect(jsonPath("$.data[0].assignmentId").value("assignment-1"))
            .andExpect(jsonPath("$.data[0].studentId").value("rvg9395"));
    }

    @Test
    void uploadingSameFeedbackFileReplacesExistingRow() throws Exception {
        String submissionId = createSubmission("assignment-1", "rvg9395", "Solution.java", "class Solution {}".getBytes());
        uploadFeedback(submissionId, "Feedback.txt", "first".getBytes());
        String feedbackId = jdbcTemplate.queryForObject(
            "SELECT id FROM feedback WHERE submission_id = ?",
            String.class,
            submissionId
        );

        uploadFeedback(submissionId, "Feedback.txt", "second".getBytes());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback WHERE submission_id = ? AND file_name = ?",
            Integer.class,
            submissionId,
            "Feedback.txt"
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
        org.junit.jupiter.api.Assertions.assertEquals(feedbackId, jdbcTemplate.queryForObject(
            "SELECT id FROM feedback WHERE submission_id = ? AND file_name = ?",
            String.class,
            submissionId,
            "Feedback.txt"
        ));

        mockMvc.perform(get("/api/v1/feedback/{feedbackId}/download", feedbackId))
            .andExpect(status().isOk())
            .andExpect(content().bytes("second".getBytes()));
    }

    @Test
    void downloadFeedbackReturnsStoredFileContents() throws Exception {
        String submissionId = createSubmission("assignment-1", "rvg9395", "Solution.java", "class Solution {}".getBytes());
        byte[] content = "feedback comments".getBytes();
        uploadFeedback(submissionId, "Feedback.txt", content);
        String feedbackId = jdbcTemplate.queryForObject(
            "SELECT id FROM feedback WHERE submission_id = ?",
            String.class,
            submissionId
        );

        mockMvc.perform(get("/api/v1/feedback/{feedbackId}/download", feedbackId))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"Feedback.txt\"")))
            .andExpect(content().bytes(content));
    }

    @Test
    void deleteFeedbackRemovesMetadataAndFile() throws Exception {
        String submissionId = createSubmission("assignment-1", "rvg9395", "Solution.java", "class Solution {}".getBytes());
        uploadFeedback(submissionId, "Feedback.txt", "feedback comments".getBytes());
        String feedbackId = jdbcTemplate.queryForObject(
            "SELECT id FROM feedback WHERE submission_id = ?",
            String.class,
            submissionId
        );
        String storedPath = jdbcTemplate.queryForObject(
            "SELECT file_path FROM feedback WHERE id = ?",
            String.class,
            feedbackId
        );

        mockMvc.perform(delete("/api/v1/feedback/{feedbackId}", feedbackId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feedback WHERE id = ?", Integer.class, feedbackId);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(Path.of(storedPath)));
    }

    private String createSubmission(String assignmentId, String studentId, String fileName, byte[] content) throws Exception {
        jdbcTemplate.update(
            """
            INSERT OR IGNORE INTO assignments (id, file_name, term, course, title, deadline, published_at, file_path, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            assignmentId,
            "spec.md",
            "fall2026",
            "daa",
            "Assignment",
            4102444740000L,
            System.currentTimeMillis(),
            STORAGE_ROOT.resolve("fall2026/daa/" + assignmentId + "/publish/spec.md").toString(),
            "assignment-hash"
        );
        String sha256 = FileHasher.sha256Hex(content);
        MockMultipartFile file = new MockMultipartFile("file", fileName, MediaType.TEXT_PLAIN_VALUE, content);

        mockMvc.perform(
                multipart("/api/v1/submissions/{term}/{course}/{assignmentId}", "fall2026", "daa", assignmentId)
                    .file(file)
                    .param("studentId", studentId)
                    .header("X-File-Sha256", sha256)
            )
            .andExpect(status().isOk());

        return jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE assignment_id = ? AND student_id = ? ORDER BY submitted_at DESC LIMIT 1",
            String.class,
            assignmentId,
            studentId
        );
    }

    private void uploadFeedback(String submissionId, String fileName, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, MediaType.TEXT_PLAIN_VALUE, content);
        mockMvc.perform(
                multipart("/api/v1/instructor/feedback/{submissionId}", submissionId)
                    .file(file)
            )
            .andExpect(status().isOk());
    }
}
