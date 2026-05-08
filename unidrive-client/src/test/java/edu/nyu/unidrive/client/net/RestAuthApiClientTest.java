package edu.nyu.unidrive.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.nyu.unidrive.common.dto.LoginResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class RestAuthApiClientTest {

    @Test
    void loginPostsJsonAndParsesResponse() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestAuthApiClient client = new RestAuthApiClient("http://localhost:8080", restTemplate);

        server.expect(requestTo("http://localhost:8080/api/v1/auth/login"))
            .andExpect(method(POST))
            .andExpect(content().string("{\"email\":\"rvg9395@nyu.edu\",\"password\":\"password123\"}"))
            .andRespond(withSuccess(
                """
                {
                  "status": "ok",
                  "data": {
                    "userId": "rvg9395",
                    "name": "Rishikesh",
                    "email": "rvg9395@nyu.edu",
                    "role": "STUDENT",
                    "accessToken": "mock-sso-token-rvg9395"
                  },
                  "message": "Login successful."
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        LoginResponse response = client.login("rvg9395@nyu.edu", "password123");

        assertEquals("rvg9395", response.userId());
        assertEquals("Rishikesh", response.name());
        assertEquals("rvg9395@nyu.edu", response.email());
        assertEquals("STUDENT", response.role());
        assertEquals("mock-sso-token-rvg9395", response.accessToken());
        server.verify();
    }

    @Test
    void invalidCredentialsSurfaceServerMessage() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestAuthApiClient client = new RestAuthApiClient("http://localhost:8080", restTemplate);

        server.expect(requestTo("http://localhost:8080/api/v1/auth/login"))
            .andExpect(method(POST))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"error\",\"message\":\"Invalid email or password.\"}"));

        IOException exception = assertThrows(
            IOException.class,
            () -> client.login("rvg9395@nyu.edu", "wrong")
        );
        assertEquals("Invalid email or password.", exception.getMessage());
        server.verify();
    }

    @Test
    void unauthorizedWithoutBodyFallsBackToFriendlyMessage() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestAuthApiClient client = new RestAuthApiClient("http://localhost:8080", restTemplate);

        server.expect(requestTo("http://localhost:8080/api/v1/auth/login"))
            .andExpect(method(POST))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        IOException exception = assertThrows(
            IOException.class,
            () -> client.login("rvg9395@nyu.edu", "wrong")
        );
        assertEquals("Invalid email or password.", exception.getMessage());
        server.verify();
    }
}
