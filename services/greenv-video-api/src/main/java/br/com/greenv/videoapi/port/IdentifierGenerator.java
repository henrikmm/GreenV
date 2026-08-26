package br.com.greenv.videoapi.port;

import java.util.UUID;

/** Generates application-owned identifiers independently from their UUID provider. */
public interface IdentifierGenerator {

    UUID next();
}
