package edu.nyu.unidrive.client.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nyu.unidrive.common.dto.LoginRequest;
import edu.nyu.unidrive.common.dto.LoginResponse;
import java.io.IOException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

public final class RestAuthApiClient implements AuthApiClient {

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RestAuthApiClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LoginResponse login(String email, String password) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> entity = new HttpEntity<>(new LoginRequest(email, password), headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/login",
                entity,
                String.class
            );
        } catch (HttpStatusCodeException exception) {
            throw new IOException(friendlyErrorMessage(exception));
        } catch (ResourceAccessException exception) {
            throw new IOException("Could not reach the server. Please try again.");
        }
        JsonNode dataNode = objectMapper.readTree(response.getBody()).path("data");
        return new LoginResponse(
            dataNode.path("userId").asText(),
            dataNode.path("name").asText(),
            dataNode.path("email").asText(),
            dataNode.path("role").asText(),
            dataNode.path("accessToken").asText()
        );
    }

    private String friendlyErrorMessage(HttpStatusCodeException exception) {
        String message = parseErrorMessage(exception.getResponseBodyAsString());
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            return "Invalid email or password.";
        }
        if (exception.getStatusCode() == HttpStatus.BAD_REQUEST) {
            return "Please check your email and password and try again.";
        }
        return "Login failed (" + exception.getStatusCode().value() + "). Please try again.";
    }

    private String parseErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body).path("message");
            return node.isMissingNode() ? null : node.asText(null);
        } catch (IOException ignored) {
            return null;
        }
    }
}
