package com.funding.api.auth;

import com.funding.domain.Role;

/**
 * The signed-in user behind a funder API call (spec 002 FR-11): {@code id} is the OIDC token's
 * {@code sub}, {@code role} comes from the {@link UserDirectory}. Controllers take it as an argument.
 */
public record Actor(String id, Role role) {}
