package edu.nyu.unidrive.server.controller;

import edu.nyu.unidrive.common.dto.ApiResponse;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import edu.nyu.unidrive.server.service.FeedbackService;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
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
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/api/v1/instructor/feedback/{submissionId}")
    public ResponseEntity<ApiResponse<FeedbackSummaryResponse>> uploadFeedback(
        @PathVariable("submissionId") String submissionId,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        try {
            FeedbackSummaryResponse response = feedbackService.uploadFeedback(submissionId, file);
            return ResponseEntity.ok(ApiResponse.ok(response, "Feedback uploaded successfully."));
        } catch (FeedbackService.SubmissionNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api/v1/feedback")
    public ResponseEntity<ApiResponse<List<FeedbackSummaryResponse>>> listFeedback(@RequestParam("studentId") String studentId) {
        return ResponseEntity.ok(ApiResponse.ok(feedbackService.listFeedback(studentId), "Feedback retrieved successfully."));
    }

    @GetMapping("/api/v1/feedback/{feedbackId}/download")
    public ResponseEntity<ByteArrayResource> downloadFeedback(@PathVariable("feedbackId") String feedbackId) throws Exception {
        try {
            FeedbackService.DownloadedFeedback download = feedbackService.loadFeedback(feedbackId);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .body(new ByteArrayResource(download.content()));
        } catch (FeedbackService.FeedbackNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/api/v1/feedback/{feedbackId}")
    public ResponseEntity<ApiResponse<Void>> deleteFeedback(@PathVariable("feedbackId") String feedbackId) throws IOException {
        try {
            feedbackService.deleteFeedback(feedbackId);
            return ResponseEntity.ok(ApiResponse.ok(null, "Feedback deleted successfully."));
        } catch (FeedbackService.FeedbackNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
