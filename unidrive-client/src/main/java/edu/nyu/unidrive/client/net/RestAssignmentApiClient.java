package edu.nyu.unidrive.client.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nyu.unidrive.common.dto.AssignmentSummaryResponse;
import edu.nyu.unidrive.common.workspace.CoursePath;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;

public final class RestAssignmentApiClient implements AssignmentApiClient {

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RestAssignmentApiClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<AssignmentSummaryResponse> listAssignments(String term, String courseSlug) throws IOException {
        String url = baseUrl + "/api/v1/assignments?term=" + term + "&course=" + courseSlug;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        JsonNode dataNode = objectMapper.readTree(response.getBody()).path("data");
        List<AssignmentSummaryResponse> assignments = new ArrayList<>();
        for (JsonNode assignmentNode : dataNode) {
            assignments.add(new AssignmentSummaryResponse(
                assignmentNode.path("assignmentId").asText(),
                assignmentNode.path("term").asText(null),
                assignmentNode.path("course").asText(null),
                assignmentNode.path("title").asText(),
                assignmentNode.path("fileName").asText(),
                assignmentNode.path("sha256").asText(),
                assignmentNode.path("deadline").asText(null)
            ));
        }
        return assignments;
    }

    @Override
    public DownloadedFile downloadAssignment(String assignmentId, String fileName) throws IOException {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("api", "v1", "assignments", assignmentId, "download")
                .queryParam("fileName", fileName)
                .build()
                .encode()
                .toUri();
            ResponseEntity<byte[]> response = restTemplate.getForEntity(
                uri,
                byte[].class
            );
            return new DownloadedFile(extractFileName(response), response.getBody() == null ? new byte[0] : response.getBody());
        } catch (RestClientException exception) {
            throw new IOException("Failed to download assignment " + assignmentId + "/" + fileName, exception);
        }
    }

    @Override
    public AssignmentSummaryResponse publishAssignment(CoursePath coursePath, String title, String deadline, Path file) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("title", title);
        body.add("deadline", deadline);
        body.add("file", new NamedByteArrayResource(file.getFileName().toString(), Files.readAllBytes(file)));

        String url = baseUrl + "/api/v1/instructor/assignments/" + coursePath.term()
            + "/" + coursePath.courseSlug()
            + "/" + coursePath.assignmentId();
        ResponseEntity<String> response = restTemplate.postForEntity(
            url,
            new HttpEntity<>(body, headers),
            String.class
        );
        JsonNode dataNode = objectMapper.readTree(response.getBody()).path("data");
        return new AssignmentSummaryResponse(
            dataNode.path("assignmentId").asText(),
            dataNode.path("term").asText(null),
            dataNode.path("course").asText(null),
            dataNode.path("title").asText(),
            dataNode.path("fileName").asText(),
            dataNode.path("sha256").asText(),
            dataNode.path("deadline").asText(null)
        );
    }

    @Override
    public void deleteAssignment(String assignmentId, String fileName) throws IOException {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("api", "v1", "instructor", "assignments", assignmentId)
                .queryParam("fileName", fileName)
                .build()
                .encode()
                .toUri();
            restTemplate.delete(uri);
        } catch (HttpClientErrorException.NotFound ignored) {
        } catch (RestClientException exception) {
            throw new IOException("Failed to delete assignment " + assignmentId + "/" + fileName, exception);
        }
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
