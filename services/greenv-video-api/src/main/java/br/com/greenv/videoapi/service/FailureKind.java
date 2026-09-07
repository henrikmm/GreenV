package br.com.greenv.videoapi.service;

/** Transport-neutral classification used by inbound adapters to map application failures. */
public enum FailureKind {
    INVALID_INPUT,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    PAYLOAD_TOO_LARGE,
    DEPENDENCY_UNAVAILABLE,
    INTERNAL_ERROR
}
