package edu.nyu.unidrive.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class RestFeedbackApiClientTest {

    @Test
    void listFeedbackParsesResponseEnvelope() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestFeedbackApiClient client = new RestFeedbackApiClient("http://localhost:8080", restTemplate);

        server.expect(requestTo("http://localhost:8080/api/v1/feedback?studentId=rvg9395"))
            .andExpect(method(GET))
            .andRespond(withSuccess(
                """
                {
                  "status": "ok",
                  "data": [
                    {
                      "feedbackId": "feedback-1",
                      "submissionId": "submission-1",
                      "term": "fall2026",
                      "course": "daa",
                      "assignmentId": "hw1",
                      "studentId": "rvg9395",
                      "fileName": "Feedback.txt",
                      "sha256": "hash-1"
                    }
                  ],
                  "message": "Feedback retrieved successfully."
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        List<FeedbackSummaryResponse> feedback = client.listFeedback("rvg9395");

        assertEquals(1, feedback.size());
        assertEquals("feedback-1", feedback.getFirst().getFeedbackId());
        assertEquals("fall2026", feedback.getFirst().getTerm());
        assertEquals("daa", feedback.getFirst().getCourse());
        assertEquals("hw1", feedback.getFirst().getAssignmentId());
        assertEquals("rvg9395", feedback.getFirst().getStudentId());
        server.verify();
    }

    @Test
    void downloadFeedbackFetchesBytesAndFilename() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestFeedbackApiClient client = new RestFeedbackApiClient("http://localhost:8080", restTemplate);

        server.expect(requestTo("http://localhost:8080/api/v1/feedback/feedback-1/download"))
            .andExpect(method(GET))
            .andRespond(withSuccess("feedback bytes", MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"Feedback.txt\""));

        DownloadedFile file = client.downloadFeedback("feedback-1");

        assertEquals("Feedback.txt", file.fileName());
        assertEquals("feedback bytes", new String(file.content()));
        server.verify();
    }
}
