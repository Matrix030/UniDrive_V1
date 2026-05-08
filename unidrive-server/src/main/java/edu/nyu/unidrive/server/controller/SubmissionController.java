package edu.nyu.unidrive.server.controller;

import edu.nyu.unidrive.common.dto.ApiResponse;
import edu.nyu.unidrive.common.dto.SubmissionSummaryResponse;
import edu.nyu.unidrive.common.dto.SubmissionUploadResponse;
import edu.nyu.unidrive.server.service.SubmissionService;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping("/api/v1/submissions/{submissionId}/download")
    public ResponseEntity<ByteArrayResource> downloadSubmission(@PathVariable("submissionId") String submissionId) throws Exception {
        try {
            SubmissionService.DownloadedSubmission download = submissionService.loadSubmission(submissionId);
            ByteArrayResource resource = new ByteArrayResource(download.content());

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .body(resource);
        } catch (SubmissionService.SubmissionNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api/v1/submissions")
    public ResponseEntity<ApiResponse<List<SubmissionSummaryResponse>>> listSubmissions(
        @RequestParam("term") String term,
        @RequestParam("course") String course,
        @RequestParam("assignmentId") String assignmentId,
        @RequestParam(value = "studentId", required = false) String studentId
    ) {
        List<SubmissionSummaryResponse> submissions = submissionService.listSubmissions(term, course, assignmentId, studentId);
        return ResponseEntity.ok(ApiResponse.ok(submissions, "Submissions retrieved successfully."));
    }

    @PostMapping("/api/v1/submissions/{term}/{course}/{assignmentId}")
    public ResponseEntity<ApiResponse<?>> uploadSubmission(
        @PathVariable("term") String term,
        @PathVariable("course") String course,
        @PathVariable("assignmentId") String assignmentId,
        @RequestParam("studentId") String studentId,
        @RequestHeader("X-File-Sha256") String providedSha256,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        try {
            SubmissionUploadResponse response = submissionService.storeSubmission(
                term,
                course,
                assignmentId,
                studentId,
                providedSha256,
                file
            );

            return ResponseEntity.ok(ApiResponse.ok(response, "Submission uploaded successfully."));
        } catch (SubmissionService.HashMismatchException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("Uploaded file hash did not match the provided SHA-256."));
        } catch (SubmissionService.AssignmentNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Assignment was not found."));
        } catch (SubmissionService.DeadlinePassedException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Assignment deadline has passed."));
        }
    }

    @DeleteMapping("/api/v1/submissions/{submissionId}")
    public ResponseEntity<ApiResponse<Void>> deleteSubmission(@PathVariable("submissionId") String submissionId) throws Exception {
        try {
            submissionService.deleteSubmission(submissionId);
            return ResponseEntity.ok(ApiResponse.ok(null, "Submission deleted successfully."));
        } catch (SubmissionService.SubmissionNotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (SubmissionService.DeadlinePassedException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Assignment deadline has passed."));
        }
    }
}
