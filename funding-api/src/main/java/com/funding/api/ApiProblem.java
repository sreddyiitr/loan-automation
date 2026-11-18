package com.funding.api;

import org.springframework.http.HttpStatus;

/** An RFC 9457 problem with a machine-readable {@code reason} (AS-10.9). */
public class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String reason;

    public ApiProblem(HttpStatus status, String reason, String detail) {
        super(detail);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() { return status; }
    public String reason() { return reason; }
}
