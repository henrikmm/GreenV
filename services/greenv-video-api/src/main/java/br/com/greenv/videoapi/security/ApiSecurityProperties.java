package br.com.greenv.videoapi.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "greenv.security")
public record ApiSecurityProperties(
        @NotBlank @Size(min = 32) String apiToken) {}
