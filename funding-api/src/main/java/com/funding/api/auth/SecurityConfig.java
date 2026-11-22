package com.funding.api.auth;

import com.funding.domain.Role;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spec 002 FR-09, FR-11, plan §4: the funder API is an OAuth2 Resource Server. Spring Security
 * checks the bearer token (signature from the issuer's JWKS, {@code iss}, audience
 * {@code funding-api}, {@code exp}, {@code nbf}) from {@code spring.security.oauth2.resourceserver.jwt.*};
 * the role comes from the {@link UserDirectory}. Other paths keep their own access: the start API
 * ({@code X-Trigger-Api-Key}), the local dev endpoints, Swagger and actuator.
 */
@Configuration
@EnableConfigurationProperties(UserDirectory.class)
public class SecurityConfig implements WebMvcConfigurer {

    /** The funder API (spec 001 US-10, spec 002 §5). */
    public static final String[] FUNDER_PATHS = {
        "/api/v1/applications/**", "/api/v1/versions/**", "/api/v1/stip-codes/**", "/api/v1/settings/**",
        "/api/v1/me", "/api/v1/users/**"};

    @Bean
    SecurityFilterChain funderApi(HttpSecurity http, UserDirectory users) throws Exception {
        JwtAuthenticationConverter tokens = new JwtAuthenticationConverter();
        tokens.setJwtGrantedAuthoritiesConverter(users.authorities());
        String[] roles = Arrays.stream(Role.values()).map(Role::name).toArray(String[]::new);
        http.csrf(AbstractHttpConfigurer::disable)   // stateless bearer-token API: no cookies, no CSRF
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(FUNDER_PATHS).hasAnyRole(roles).anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(tokens))
                        .authenticationEntryPoint((req, resp, e) -> unauthorized(resp))
                        .accessDeniedHandler((req, resp, e) -> unknownUser(resp)))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, resp, ex) -> unauthorized(resp))
                        .accessDeniedHandler((req, resp, ex) -> unknownUser(resp)));
        return http.build();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ActorArgumentResolver());
    }

    private static void unauthorized(HttpServletResponse resp) throws IOException {
        resp.setHeader("WWW-Authenticate", "Bearer");
        problem(resp, 401, "UNAUTHORIZED", "a valid bearer token is required (AS-10.9)");
    }

    /** The only role check at this layer: every listed user has a role, so a refusal means an unlisted user. */
    private static void unknownUser(HttpServletResponse resp) throws IOException {
        problem(resp, 403, "UNKNOWN_USER", "the signed-in user is not in the user directory (AS-1.1)");
    }

    private static void problem(HttpServletResponse resp, int status, String reason, String detail) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/problem+json");
        resp.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + reason + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"reason\":\"" + reason + "\"}");
    }
}
