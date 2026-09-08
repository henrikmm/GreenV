package br.com.greenv.videoapi.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** RFC 6749 §5.1 token response, used by the native and machine clients. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_token") String refreshToken) {

    static TokenResponse bearer(String accessToken, long expiresIn, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken);
    }
}
