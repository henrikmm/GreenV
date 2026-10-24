package br.com.greenv.videoapi.domain;

/** Roles an authenticated principal can carry. Names match the Spring Security authority. */
public enum AuthRole {
    ADMIN,
    OPERATOR,
    CAPTURE_CLIENT,
    /**
     * The public demonstration account behind {@code POST /v2/auth/demo}. It reads everything an
     * operator reads and writes nothing: a write from it could open a capture or an order, and
     * either one can wake the GPU that bills. ApiSecurityConfiguration enforces that.
     */
    DEMO;

    public String authority() {
        return "ROLE_" + name();
    }

    public static AuthRole of(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
