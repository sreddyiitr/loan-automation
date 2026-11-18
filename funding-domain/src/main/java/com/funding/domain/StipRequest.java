package com.funding.domain;

/** A stip a check wants raised. Creation is idempotent per (version, code, check) (FR-07). */
public record StipRequest(String code, String description) {}
