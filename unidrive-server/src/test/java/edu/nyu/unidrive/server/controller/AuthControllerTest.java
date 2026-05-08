package edu.nyu.unidrive.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite:target/test-auth.db"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearUsers() {
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    void loginReturnsExistingUser() throws Exception {
        jdbcTemplate.update("INSERT INTO users (id, name, role) VALUES (?, ?, ?)",
            "rvg9395", "Rishikesh", "STUDENT");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rvg9395@nyu.edu\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.data.userId").value("rvg9395"))
            .andExpect(jsonPath("$.data.name").value("Rishikesh"))
            .andExpect(jsonPath("$.data.email").value("rvg9395@nyu.edu"))
            .andExpect(jsonPath("$.data.role").value("STUDENT"))
            .andExpect(jsonPath("$.data.accessToken").value("mock-sso-token-rvg9395"));
    }

    @Test
    void loginUpdatesRoleForExistingUser() throws Exception {
        jdbcTemplate.update("INSERT INTO users (id, name, role) VALUES (?, ?, ?)",
            "instructor_rvg0000", "Instructor Demo", "STUDENT");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rvg0000@nyu.edu\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("INSTRUCTOR"));

        String role = jdbcTemplate.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, "instructor_rvg0000");
        org.junit.jupiter.api.Assertions.assertEquals("INSTRUCTOR", role);
    }

    @Test
    void loginAutoCreatesUserWhenAbsent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"instructor@nyu.edu\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("instructor_rvg0000"))
            .andExpect(jsonPath("$.data.role").value("INSTRUCTOR"));

        String role = jdbcTemplate.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, "instructor_rvg0000");
        org.junit.jupiter.api.Assertions.assertEquals("INSTRUCTOR", role);
    }

    @Test
    void loginSupportsAdditionalDemoAccounts() throws Exception {
        expectLogin("student2@nyu.edu", "ow2130", "STUDENT");
        expectLogin("student3@nyu.edu", "js1234", "STUDENT");
        expectLogin("ta@nyu.edu", "instructor_ow0000", "INSTRUCTOR");
    }

    @Test
    void loginRejectsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@nyu.edu\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void loginRejectsBlankEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"\",\"password\":\"password123\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void loginRejectsBlankPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student@nyu.edu\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    private void expectLogin(String email, String userId, String role) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(userId))
            .andExpect(jsonPath("$.data.role").value(role));
    }
}
