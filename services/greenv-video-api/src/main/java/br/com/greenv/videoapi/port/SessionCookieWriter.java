package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.AuthSessionTokens;
import java.util.List;

/**
 * Renders the browser session as {@code Set-Cookie} header values.
 *
 * <p>A port rather than a helper so an inbound adapter keeps depending only on interfaces, and so
 * the cookie attributes that make a stolen token useless live in one place instead of being
 * repeated at every call site.
 */
public interface SessionCookieWriter {

    /** Header values that open a session. */
    List<String> open(AuthSessionTokens tokens);

    /** Header values that end one, expiring every cookie the session used. */
    List<String> close();
}
