package br.com.greenv.videoapi.domain;

/** Roles an authenticated principal can carry. Names match the Spring Security authority. */
public enum AuthRole {
    ADMIN,
    OPERATOR,
    CAPTURE_CLIENT;

    public String authority() {
        return "ROLE_" + name();
    }

    public static AuthRole of(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
