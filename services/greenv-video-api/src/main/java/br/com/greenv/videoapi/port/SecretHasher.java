package br.com.greenv.videoapi.port;

/** Hashes and verifies passwords and client secrets, independently from the algorithm behind it. */
public interface SecretHasher {

    String hash(String rawSecret);

    /** Constant-time as far as the algorithm allows; never short-circuits on a missing hash. */
    boolean matches(String rawSecret, String storedHash);
}
