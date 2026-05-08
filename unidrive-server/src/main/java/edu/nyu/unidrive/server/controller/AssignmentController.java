package edu.nyu.unidrive.server.controller;

import edu.nyu.unidrive.common.dto.ApiResponse;
import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.server.service.AssignmentService;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PostMapping("/api/v1/instructor/assignments/{term}/{course}/{assignmentId}")
    public ResponseEntity<ApiResponse<AssignmentSummaryResponse>> publishAssignment(
        @PathVariable("term") String term,
        @PathVariable("course") String course,
        @PathVariable("assignmentId") String assignmentId,
        @RequestParam("title") String title,
        @RequestParam("deadline") String deadline,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        try {
            AssignmentSummaryResponse response = assignmentService.publishAssignment(term, course, assignmentId, title, deadline, file);
            return ResponseEntity.ok(ApiResponse.ok(response, "Assignment published successfully."));
        } catch (AssignmentService.InvalidDeadlineException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Assignment deadline must be an ISO-8601 UTC instant."));
        }
    }

    @GetMapping("/api/v1/assignments")
    public ResponseEntity<ApiResponse<List<AssignmentSummaryResponse>>> listAssignments(
        @RequestParam("term") String term,
        @RequestParam("course") String course
    ) {
        return ResponseEntity.ok(
            ApiResponse.ok(assignmentService.listAssignments(term, course), "Assignments retrieved successfully.")
        );
    }

    @GetMapping("/api/v1/assignments/{assignmentId}/download")
    public ResponseEntity<ByteArrayResource> downloadAssignment(
        @PathVariable("assignmentId") String assignmentId,
        @RequestParam("fileName") String fileName
    ) throws Exception {
        try {
            AssignmentService.DownloadedAssignment download = assignmentService.loadAssignment(assignmentId, fileName);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .body(new ByteArrayResource(download.content()));
        } catch (AssignmentService.AssignmentNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/api/v1/instructor/assignments/{assignmentId}")
    public ResponseEntity<ApiResponse<Void>> deleteAssignment(
        @PathVariable("assignmentId") String assignmentId,
        @RequestParam("fileName") String fileName
    ) throws Exception {
        try {
            assignmentService.deleteAssignment(assignmentId, fileName);
            return ResponseEntity.ok(ApiResponse.ok(null, "Assignment deleted successfully."));
        } catch (AssignmentService.AssignmentNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
