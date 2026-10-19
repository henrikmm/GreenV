package br.com.greenv.videoapi.service;

/** Transport-neutral classification used by inbound adapters to map application failures. */
public enum FailureKind {
    INVALID_INPUT,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    PAYLOAD_TOO_LARGE,
    /** A body was sent without saying how long it is, where the length decides whether to read it. */
    LENGTH_REQUIRED,
    UNSUPPORTED_MEDIA_TYPE,
    DEPENDENCY_UNAVAILABLE,
    INTERNAL_ERROR
}
