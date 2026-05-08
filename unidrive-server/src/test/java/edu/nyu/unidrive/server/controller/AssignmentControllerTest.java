package edu.nyu.unidrive.server.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    "unidrive.storage.root=target/test-assignment-storage",
    "spring.datasource.url=jdbc:sqlite:target/test-assignments.db",
    "spring.servlet.multipart.max-file-size=256MB",
    "spring.servlet.multipart.max-request-size=256MB",
    "server.tomcat.max-swallow-size=256MB",
    "server.tomcat.max-http-form-post-size=256MB"
})
class AssignmentControllerTest {

    private static final Path STORAGE_ROOT = Path.of("target/test-assignment-storage");
    private static final String TERM = "fall2026";
    private static final String COURSE = "daa";
    private static final String FUTURE_DEADLINE = "2099-12-31T23:59:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearState() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_ROOT);
        Files.createDirectories(STORAGE_ROOT);
        jdbcTemplate.execute("DELETE FROM assignments");
    }

    @Test
    void publishAssignmentStoresFileAndMetadata() throws Exception {
        byte[] content = "assignment instructions".getBytes();
        String sha256 = FileHasher.sha256Hex(content);
        String assignmentId = "hw1";
        MockMultipartFile file = new MockMultipartFile("file", "Assignment1.txt", MediaType.TEXT_PLAIN_VALUE, content);

        mockMvc.perform(
                multipart("/api/v1/instructor/assignments/{term}/{course}/{assignmentId}", TERM, COURSE, assignmentId)
                    .file(file)
                    .param("title", "Assignment 1")
                    .param("deadline", FUTURE_DEADLINE)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Assignment published successfully."))
            .andExpect(jsonPath("$.data.assignmentId").value(assignmentId))
            .andExpect(jsonPath("$.data.term").value(TERM))
            .andExpect(jsonPath("$.data.course").value(COURSE))
            .andExpect(jsonPath("$.data.title").value("Assignment 1"))
            .andExpect(jsonPath("$.data.fileName").value("Assignment1.txt"))
            .andExpect(jsonPath("$.data.sha256").value(sha256))
            .andExpect(jsonPath("$.data.deadline").value(FUTURE_DEADLINE));

        Map<String, Object> saved = jdbcTemplate.queryForMap(
            "SELECT term, course, title, deadline, hash FROM assignments WHERE id = ?",
            assignmentId
        );
        org.junit.jupiter.api.Assertions.assertEquals(TERM, saved.get("term"));
        org.junit.jupiter.api.Assertions.assertEquals(COURSE, saved.get("course"));
        org.junit.jupiter.api.Assertions.assertEquals("Assignment 1", saved.get("title"));
        org.junit.jupiter.api.Assertions.assertEquals(4102444740000L, ((Number) saved.get("deadline")).longValue());
        org.junit.jupiter.api.Assertions.assertEquals(sha256, saved.get("hash"));

        try (Stream<Path> storedFiles = Files.walk(STORAGE_ROOT)) {
            org.junit.jupiter.api.Assertions.assertEquals(1, storedFiles.filter(Files::isRegularFile).count());
        }

        Path expected = STORAGE_ROOT.resolve(TERM).resolve(COURSE).resolve(assignmentId).resolve("publish").resolve("Assignment1.txt");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(expected), "expected file at " + expected);
    }

    @Test
    void publishAssignmentAcceptsLargeMultipartUploads() throws Exception {
        byte[] content = new byte[3 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        String sha256 = FileHasher.sha256Hex(content);
        MockMultipartFile file = new MockMultipartFile("file", "BigAssignment.txt", MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

        mockMvc.perform(
                multipart("/api/v1/instructor/assignments/{term}/{course}/{assignmentId}", TERM, COURSE, "big-1")
                    .file(file)
                    .param("title", "Big Assignment")
                    .param("deadline", FUTURE_DEADLINE)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.data.fileName").value("BigAssignment.txt"))
            .andExpect(jsonPath("$.data.sha256").value(sha256));
    }

    @Test
    void publishAssignmentRejectsInvalidDeadline() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Assignment1.txt", MediaType.TEXT_PLAIN_VALUE, "content".getBytes());

        mockMvc.perform(
                multipart("/api/v1/instructor/assignments/{term}/{course}/{assignmentId}", TERM, COURSE, "hw1")
                    .file(file)
                    .param("title", "Assignment 1")
                    .param("deadline", "tomorrow")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Assignment deadline must be an ISO-8601 UTC instant."));
    }

    @Test
    void listAssignmentsReturnsPublishedAssignmentsForTermAndCourse() throws Exception {
        publishAssignment("hw1", "Assignment 1", "Assignment1.txt", "one".getBytes(), TERM, COURSE);
        publishAssignment("hw2", "Assignment 2", "Assignment2.txt", "two".getBytes(), TERM, COURSE);
        publishAssignment("hw3", "Java Hello", "Hello.java", "three".getBytes(), TERM, "java");

        mockMvc.perform(get("/api/v1/assignments").param("term", TERM).param("course", COURSE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.message").value("Assignments retrieved successfully."))
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void downloadAssignmentReturnsStoredFileContents() throws Exception {
        byte[] content = "assignment file".getBytes();
        publishAssignment("hw1", "Assignment 1", "Assignment1.txt", content, TERM, COURSE);

        mockMvc.perform(get("/api/v1/assignments/{assignmentId}/download", "hw1").param("fileName", "Assignment1.txt"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"Assignment1.txt\"")))
            .andExpect(content().bytes(content));
    }

    @Test
    void publishAssignmentAcceptsMultipleFilesUnderSameAssignmentId() throws Exception {
        publishAssignment("hw1", "Assignment 1", "spec.md", "spec".getBytes(), TERM, COURSE);
        publishAssignment("hw1", "Assignment 1", "starter.zip", "starter".getBytes(), TERM, COURSE);

        mockMvc.perform(get("/api/v1/assignments").param("term", TERM).param("course", COURSE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/assignments/{assignmentId}/download", "hw1").param("fileName", "spec.md"))
            .andExpect(status().isOk())
            .andExpect(content().bytes("spec".getBytes()));
        mockMvc.perform(get("/api/v1/assignments/{assignmentId}/download", "hw1").param("fileName", "starter.zip"))
            .andExpect(status().isOk())
            .andExpect(content().bytes("starter".getBytes()));
    }

    private void publishAssignment(
        String assignmentId,
        String title,
        String fileName,
        byte[] content,
        String term,
        String course
    ) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, MediaType.TEXT_PLAIN_VALUE, content);
        mockMvc.perform(
                multipart("/api/v1/instructor/assignments/{term}/{course}/{assignmentId}", term, course, assignmentId)
                    .file(file)
                    .param("title", title)
                    .param("deadline", FUTURE_DEADLINE)
            )
            .andExpect(status().isOk());
    }
}
