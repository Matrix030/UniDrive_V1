package edu.nyu.unidrive.client.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nyu.unidrive.common.dto.FeedbackSummaryResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

public final class RestFeedbackApiClient implements FeedbackApiClient {

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RestFeedbackApiClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<FeedbackSummaryResponse> listFeedback(String studentId) throws IOException {
        ResponseEntity<String> response = restTemplate.getForEntity(
            baseUrl + "/api/v1/feedback?studentId=" + studentId,
            String.class
        );
        JsonNode dataNode = objectMapper.readTree(response.getBody()).path("data");
        List<FeedbackSummaryResponse> feedbackRows = new ArrayList<>();
        for (JsonNode feedbackNode : dataNode) {
            feedbackRows.add(new FeedbackSummaryResponse(
                feedbackNode.path("feedbackId").asText(),
                feedbackNode.path("submissionId").asText(),
                feedbackNode.path("term").asText(),
                feedbackNode.path("course").asText(),
                feedbackNode.path("assignmentId").asText(),
                feedbackNode.path("studentId").asText(),
                feedbackNode.path("fileName").asText(),
                feedbackNode.path("sha256").asText()
            ));
        }
        return feedbackRows;
    }

    @Override
    public DownloadedFile downloadFeedback(String feedbackId) {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(
            baseUrl + "/api/v1/feedback/" + feedbackId + "/download",
            byte[].class
        );
        return new DownloadedFile(extractFileName(response), response.getBody() == null ? new byte[0] : response.getBody());
    }

    @Override
    public FeedbackSummaryResponse uploadFeedback(String submissionId, Path file) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedByteArrayResource(file.getFileName().toString(), Files.readAllBytes(file)));

        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/api/v1/instructor/feedback/" + submissionId,
            new HttpEntity<>(body, headers),
            String.class
        );
        JsonNode dataNode = objectMapper.readTree(response.getBody()).path("data");
        return new FeedbackSummaryResponse(
            dataNode.path("feedbackId").asText(),
            dataNode.path("submissionId").asText(),
            dataNode.path("term").asText(),
            dataNode.path("course").asText(),
            dataNode.path("assignmentId").asText(),
            dataNode.path("studentId").asText(),
            dataNode.path("fileName").asText(),
            dataNode.path("sha256").asText()
        );
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(String filename, byte[] byteArray) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    private String extractFileName(ResponseEntity<?> response) {
        String contentDisposition = response.getHeaders().getFirst("Content-Disposition");
        if (contentDisposition == null) {
            return "download.bin";
        }
        int start = contentDisposition.indexOf("filename=\"");
        if (start < 0) {
            return "download.bin";
        }
        return contentDisposition.substring(start + 10, contentDisposition.length() - 1);
    }
}
