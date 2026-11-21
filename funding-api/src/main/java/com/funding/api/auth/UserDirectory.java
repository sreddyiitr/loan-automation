package com.funding.api.auth;

import com.funding.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Spec 002 D-13: the identity provider says who the user is ({@code sub}); this directory says
 * their role ({@code funding.auth.users}: user id → role). A user who isn't listed gets no role, so
 * every funder API call is refused with 403 {@code UNKNOWN_USER}.
 */
@ConfigurationProperties(prefix = "funding.auth")
public record UserDirectory(Map<String, Role> users) {

    static final String ROLE_PREFIX = "ROLE_";

    public UserDirectory {
        users = users == null ? Map.of() : Map.copyOf(users);
    }

    public Optional<Role> role(String userId) {
        return Optional.ofNullable(userId == null ? null : users.get(userId));
    }

    /** The token's authorities: {@code ROLE_<role>} for a listed user, none otherwise. */
    public Converter<Jwt, Collection<GrantedAuthority>> authorities() {
        return jwt -> role(jwt.getSubject())
                .<Collection<GrantedAuthority>>map(r -> List.of(new SimpleGrantedAuthority(ROLE_PREFIX + r.name())))
                .orElse(List.of());
    }
}
