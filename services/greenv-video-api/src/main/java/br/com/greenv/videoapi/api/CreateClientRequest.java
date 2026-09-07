package br.com.greenv.videoapi.api;

import jakarta.validation.constraints.NotBlank;

public record CreateClientRequest(
        @NotBlank String displayName,
        String role) {
}
