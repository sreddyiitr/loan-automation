package com.funding.api.auth;

import com.funding.api.ApiProblem;
import com.funding.domain.Role;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves an {@link Actor} argument from the authenticated token: its name ({@code sub}) and role authority. */
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                 WebDataBinderFactory binders) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "a bearer token is required (AS-10.9)");
        }
        Role role = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(UserDirectory.ROLE_PREFIX))
                .map(a -> Role.valueOf(a.substring(UserDirectory.ROLE_PREFIX.length())))
                .findFirst()
                .orElseThrow(() -> new ApiProblem(HttpStatus.FORBIDDEN, "UNKNOWN_USER", auth.getName() + " is not in the user directory"));
        return new Actor(auth.getName(), role);
    }
}
