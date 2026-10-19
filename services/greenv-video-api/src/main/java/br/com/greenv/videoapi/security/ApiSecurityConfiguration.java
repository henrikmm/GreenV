package br.com.greenv.videoapi.security;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class ApiSecurityConfiguration {

    /**
     * DELETE and PATCH are here for the dashboard, which edits and retires service orders. They
     * were absent, so those two verbs failed their preflight while the same routes worked from
     * curl - a difference only a browser ever sees.
     */
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /**
     * The capture client carries its segment metadata in headers, so a browser preflight fails
     * unless every one of them is listed.
     */
    private static final List<String> ALLOWED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "X-Idempotency-Key",
            "X-Content-SHA256",
            "X-Captured-At",
            "X-Duration-Millis",
            AuthCookies.CSRF_HEADER);

    /**
     * Opening a session and checking who signed a token cannot themselves require a token. Every
     * other route stays authenticated, exactly as before.
     */
    private static final String[] PUBLIC_AUTH_PATHS = {
        "/v2/auth/login",
        "/v2/auth/refresh",
        // Public so an expired access token can still end its session and clear its cookies.
        "/v2/auth/logout",
        "/v2/oauth/token",
        "/.well-known/jwks.json"
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            ApiSecurityProperties securityProperties,
            @Value("${greenv.demo.enabled:false}") boolean demoEnabled,
            @Value("${greenv.demo.allowed-origins:}") List<String> demoOrigins) throws Exception {
        var siteOrigins = demoEnabled ? demoSiteOrigins(demoOrigins) : List.<String>of();
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                    // Spring Security answers the preflight in its CorsFilter, ahead of
                    // authorization, which is why an unauthenticated OPTIONS still succeeds.
                    if (securityProperties.hasAllowedOrigins() || !siteOrigins.isEmpty()) {
                        cors.configurationSource(
                                corsConfigurationSource(securityProperties, demoEnabled, siteOrigins));
                    } else {
                        cors.disable();
                    }
                })
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> {
                    authorize
                            .requestMatchers("/actuator/health", "/actuator/health/**")
                            .permitAll()
                            .requestMatchers(PUBLIC_AUTH_PATHS)
                            .permitAll();
                    // The FIAP Next demo answers to its own pairing code, checked by the demo's
                    // use case, and its reads are public by design. Opened only while the demo
                    // is on, so a deployment without it keeps the 401 every unknown route gets.
                    if (demoEnabled) {
                        authorize.requestMatchers("/v1/demo/**").permitAll();
                    }
                    // Creating identities is reserved for the shared operational token, which is
                    // the only credential granted this authority.
                    authorize
                            .requestMatchers("/v2/identity/**")
                            .hasRole("PROVISIONER")
                            .anyRequest()
                            .authenticated();
                })
                // Both sit immediately before authorization, in the order added - which is the
                // only placement that is unambiguous: addFilterAfter against a custom filter class
                // does not resolve a position, and the filter ends up ahead of
                // SecurityContextHolderFilter, which then wipes whatever it authenticated.
                //
                // The static capture token is tried first and left untouched, so the live capture
                // pipeline keeps authenticating exactly the way it does today; the JWT filter only
                // looks at requests that filter did not claim.
                .addFilterBefore(bearerTokenFilter, AuthorizationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, AuthorizationFilter.class)
                .build();
    }

    /**
     * Origins that may read the demo and nothing else. The public demo site is kept off
     * {@code greenv.security.allowed-origins} on purpose: that list is granted credentialed CORS on
     * every route, and the demo site shares a registrable domain with the API, so its cookies would
     * travel too.
     */
    private static List<String> demoSiteOrigins(List<String> configured) {
        if (configured == null) {
            return List.of();
        }
        return configured.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
    }

    private static CorsConfigurationSource corsConfigurationSource(
            ApiSecurityProperties properties, boolean demoEnabled, List<String> siteOrigins) {
        var source = new UrlBasedCorsConfigurationSource();
        if (demoEnabled) {
            // Registered first, because the first pattern that matches wins. The demo site reads
            // public data with plain GETs and no cookie, and the phone is not a browser, so these
            // routes grant no credentials to any origin — whatever the dashboard's rule below says.
            var demo = new CorsConfiguration();
            var demoOrigins = new LinkedHashSet<>(properties.allowedOrigins());
            demoOrigins.addAll(siteOrigins);
            demo.setAllowedOrigins(List.copyOf(demoOrigins));
            demo.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
            demo.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE));
            demo.setAllowCredentials(false);
            demo.setMaxAge(Duration.ofMinutes(30));
            source.registerCorsConfiguration("/v1/demo/**", demo);
        }
        if (!properties.hasAllowedOrigins()) {
            return source;
        }
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        // The dashboard authenticates with an HttpOnly cookie, which a browser only sends
        // cross-origin when credentials are allowed. This is safe only because allowedOrigins is an
        // exact, regex-validated list with no wildcard - the two must never be relaxed together.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofMinutes(30));
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
