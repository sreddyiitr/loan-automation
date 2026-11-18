package com.funding.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error is {@code application/problem+json} (RFC 9457), with a {@code reason} code where one applies. */
@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<ProblemDetail> problem(ApiProblem e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setTitle(e.reason());
        p.setProperty("reason", e.reason());
        return respond(p);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "malformed request: " + rootMessage(e));
        p.setProperty("reason", "MALFORMED");
        return respond(p);
    }

    static ResponseEntity<ProblemDetail> respond(ProblemDetail p) {
        return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
