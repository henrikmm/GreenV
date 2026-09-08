package br.com.greenv.videoapi.token;

/** Claim names shared by the issuer and the verifier. */
public final class TokenClaims {

    /** Distinguishes an access token from anything else that might be signed later. */
    public static final String TOKEN_USE = "use";

    public static final String ACCESS = "access";

    /** RFC 9068 media type for the "typ" JOSE header, checked by JwtTypeValidator. */
    public static final String ACCESS_TOKEN_TYPE = "at+jwt";

    public static final String ROLE = "rol";

    /** The session this token belongs to; absent for the client_credentials grant. */
    public static final String SESSION_ID = "sid";

    public static final String DISPLAY_NAME = "name";

    public static final String MACHINE_CLIENT = "mch";

    /**
     * RFC 7800 confirmation claim. Holds only the SHA-256 of the fingerprint cookie, so a token
     * lifted from a log or a proxy is useless without the paired cookie.
     */
    public static final String CONFIRMATION = "cnf";

    public static final String FINGERPRINT = "fgp";

    private TokenClaims() {}
}
