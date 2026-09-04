package br.com.greenv.videoapi.port;

import java.util.Map;

/** Publishes the public half of the signing key, so anyone can check a token's origin. */
public interface JwkSetProvider {

    /** The JWK Set document, ready to serialise as {@code application/json}. */
    Map<String, Object> jwkSet();
}
