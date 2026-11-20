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

    /** AS-9.7: the DB refuses a document for an APPROVED or final version (SQLSTATE P0001 {@code DOCUMENTS_FROZEN}). */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ProblemDetail> dataAccess(org.springframework.dao.DataAccessException e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof java.sql.SQLException sql && "P0001".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("DOCUMENTS_FROZEN")) {
                String detail = sql.getMessage().substring(sql.getMessage().indexOf("DOCUMENTS_FROZEN")).lines().findFirst().orElse("");
                ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
                p.setTitle("DOCUMENTS_FROZEN");
                p.setProperty("reason", "DOCUMENTS_FROZEN");
                return respond(p);
            }
            c = c.getCause();
        }
        throw e;
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
