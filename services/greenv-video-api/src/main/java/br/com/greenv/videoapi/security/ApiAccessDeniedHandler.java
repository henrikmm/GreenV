package br.com.greenv.videoapi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * A caller who authenticated but lacks the authority gets 403, not 401 - the distinction matters:
 * 401 invites a client to present a credential, and re-presenting a valid one would not help here.
 */
@Component
final class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final String RESPONSE =
            """
            {"type":"urn:greenv:error:forbidden","title":"forbidden","status":403,\
"detail":"This credential is not allowed to perform that operation"}
            """;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(RESPONSE);
    }
}
