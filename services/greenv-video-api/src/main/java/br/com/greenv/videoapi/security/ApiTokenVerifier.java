package br.com.greenv.videoapi.security;

public interface ApiTokenVerifier {

    boolean isValid(String candidate);
}
