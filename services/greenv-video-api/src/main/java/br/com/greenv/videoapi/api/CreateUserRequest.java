package br.com.greenv.videoapi.api;

import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
        @NotBlank String email,
        @NotBlank String password,
        String displayName,
        String role) {
}
