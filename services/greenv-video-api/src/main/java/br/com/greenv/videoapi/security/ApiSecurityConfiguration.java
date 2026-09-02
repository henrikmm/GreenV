package br.com.greenv.videoapi.security;

import java.time.Duration;
import java.util.List;
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

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "OPTIONS");

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
            "X-Duration-Millis");

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiSecurityProperties securityProperties) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                    // Spring Security answers the preflight in its CorsFilter, ahead of
                    // authorization, which is why an unauthenticated OPTIONS still succeeds.
                    if (securityProperties.hasAllowedOrigins()) {
                        cors.configurationSource(corsConfigurationSource(securityProperties));
                    } else {
                        cors.disable();
                    }
                })
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(bearerTokenFilter, AuthorizationFilter.class)
                .build();
    }

    private static CorsConfigurationSource corsConfigurationSource(ApiSecurityProperties properties) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        // Credentials stay off: the client authenticates with a Bearer header, never a cookie.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofMinutes(30));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
