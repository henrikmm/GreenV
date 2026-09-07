package br.com.greenv.videoapi.domain;

import java.time.Instant;

/** A signed access token and the moment it stops being accepted. */
public record IssuedToken(String value, Instant expiresAt) {}
