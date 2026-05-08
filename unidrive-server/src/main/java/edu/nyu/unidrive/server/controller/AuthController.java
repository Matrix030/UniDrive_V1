package edu.nyu.unidrive.server.controller;

import edu.nyu.unidrive.common.dto.ApiResponse;
import edu.nyu.unidrive.common.dto.LoginRequest;
import edu.nyu.unidrive.common.dto.LoginResponse;
import edu.nyu.unidrive.server.model.UserRole;
import edu.nyu.unidrive.server.repository.UserRepository;
import edu.nyu.unidrive.server.service.MockIdentityProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserRepository userRepository;
    private final MockIdentityProvider identityProvider;

    public AuthController(UserRepository userRepository, MockIdentityProvider identityProvider) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("email is required."));
        }
        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("password is required."));
        }

        MockIdentityProvider.AuthenticatedUser authenticatedUser = identityProvider
            .authenticate(request.email(), request.password())
            .orElse(null);
        if (authenticatedUser == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Invalid email or password."));
        }
        if (!UserRole.isValid(authenticatedUser.role())) {
            return ResponseEntity.status(401).body(ApiResponse.error("Authenticated user has an invalid role."));
        }

        if (userRepository.findById(authenticatedUser.userId()).isEmpty()) {
            userRepository.save(
                authenticatedUser.userId(),
                authenticatedUser.name(),
                authenticatedUser.role(),
                authenticatedUser.passwordHash()
            );
        } else {
            userRepository.updateProfile(
                authenticatedUser.userId(),
                authenticatedUser.name(),
                authenticatedUser.role(),
                authenticatedUser.passwordHash()
            );
        }
        UserRepository.StoredUser user = userRepository.findById(authenticatedUser.userId()).orElseThrow();

        LoginResponse response = new LoginResponse(
            user.id(),
            user.name(),
            authenticatedUser.email(),
            user.role(),
            authenticatedUser.accessToken()
        );
        return ResponseEntity.ok(ApiResponse.ok(response, "Login successful."));
    }
}
