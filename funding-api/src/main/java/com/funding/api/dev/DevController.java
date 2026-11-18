package com.funding.api.dev;

import com.funding.api.ApiProblem;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.VersionRepository;
import com.funding.persistence.VersionRow;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only endpoints (FR-16), {@code local} profile only. {@code submit} plays OCR's part: it flips
 * the latest DRAFT version to PENDING_REVIEW, which runs the real path (DB trigger -> outbox ->
 * SQS -> Lambda handler -> start API -> workflow). Add version / add document arrive in slice S5.
 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/dev/applications")
public class DevController {

    private final VersionRepository versions;
    private final AuditRepository audit;

    public DevController(JdbcClient jdbc) {
        this.versions = new VersionRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
    }

    @PostMapping("/{loanApplicationId}/submit")
    @Transactional
    public Map<String, Object> submit(@PathVariable String loanApplicationId,
                                      @RequestHeader(value = "X-Funder-Id", required = false) String actor) {
        VersionRow v = versions.findLatest(loanApplicationId).orElseThrow(() ->
                new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "no application " + loanApplicationId));
        VersionRow locked = versions.lockLatest(v.applicationVersionId());
        if (!"DRAFT".equals(locked.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_DRAFT",
                    loanApplicationId + " v" + locked.versionNumber() + " is " + locked.status() + ", not DRAFT");
        }
        versions.setStatus(locked, "PENDING_REVIEW");
        audit.record("APPLICATION_VERSION", Long.toString(locked.applicationVersionId()), "STATUS_CHANGED",
                actor == null || actor.isBlank() ? "dev:submit" : actor,
                Map.of("status", "DRAFT"), Map.of("status", "PENDING_REVIEW", "reason", "SUBMITTED"));
        return Map.of("loanApplicationId", loanApplicationId, "applicationVersionId", locked.applicationVersionId(),
                "status", "PENDING_REVIEW");
    }

}
