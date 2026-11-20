package com.funding.api.dev;

import com.funding.api.ApiProblem;
import com.funding.persistence.ApplicationWriter;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.ResubmissionRefusedException;
import com.funding.persistence.SupersededException;
import com.funding.persistence.VersionRepository;
import com.funding.persistence.VersionRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only endpoints (FR-16), {@code local} profile only; they play OCR's part.
 * <ul>
 *   <li>{@code submit} flips the latest DRAFT version to PENDING_REVIEW, which runs the real path
 *       (DB trigger -> outbox -> SQS -> Lambda handler -> start API -> workflow).</li>
 *   <li>{@code versions} (S5, AS-9.1) creates the next application version as a DRAFT copy of the
 *       latest one (application fields and latest documents), with any documents in the body
 *       replacing those of the same type. The latest flag moves in one transaction; submit it to
 *       supersede the previous version's run (AS-9.2).</li>
 *   <li>{@code documents} (S5, AS-9.4) adds a new latest version of one document to the latest
 *       application version. Under review it re-runs every check (AS-1.8); once APPROVED or final
 *       the DB refuses it, 409 {@code DOCUMENTS_FROZEN} (AS-9.7).</li>
 * </ul>
 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/dev/applications")
public class DevController {

    /** A document as OCR writes it. */
    public record DevField(String key, String value, BigDecimal confidence, List<Integer> boundingBox, Integer page) {}

    public record DevSection(String name, int startPage, int endPage, List<DevField> fields) {}

    public record DevDocument(String type, String fileName, int totalPages, List<DevSection> sections) {}

    public record NewVersionRequest(List<DevDocument> documents) {}

    private static final Set<String> TYPES = Set.of("RISC", "BUYERS_ORDER", "GAP_AGREEMENT");

    private final VersionRepository versions;
    private final AuditRepository audit;
    private final ApplicationWriter writer;

    public DevController(JdbcClient jdbc) {
        this.versions = new VersionRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
        this.writer = new ApplicationWriter(jdbc);
    }

    @PostMapping("/{loanApplicationId}/submit")
    @Transactional
    public Map<String, Object> submit(@PathVariable String loanApplicationId,
                                      @RequestHeader(value = "X-Funder-Id", required = false) String actor) {
        VersionRow locked = lockLatest(loanApplicationId);
        if (!"DRAFT".equals(locked.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_DRAFT",
                    loanApplicationId + " v" + locked.versionNumber() + " is " + locked.status() + ", not DRAFT");
        }
        versions.setStatus(locked, "PENDING_REVIEW");
        audit.record("APPLICATION_VERSION", Long.toString(locked.applicationVersionId()), "STATUS_CHANGED", actor(actor, "dev:submit"),
                Map.of("status", "DRAFT"), Map.of("status", "PENDING_REVIEW", "reason", "SUBMITTED"));
        return Map.of("loanApplicationId", loanApplicationId, "applicationVersionId", locked.applicationVersionId(),
                "status", "PENDING_REVIEW");
    }

    @PostMapping("/{loanApplicationId}/versions")
    @Transactional
    public ResponseEntity<Map<String, Object>> newVersion(@PathVariable String loanApplicationId,
                                                          @RequestBody(required = false) NewVersionRequest body,
                                                          @RequestHeader(value = "X-Funder-Id", required = false) String actor) {
        VersionRow previous = lockLatest(loanApplicationId);
        Map<String, ApplicationWriter.NewDocument> docs = new LinkedHashMap<>();
        writer.latestDocuments(previous.applicationVersionId()).forEach(d -> docs.put(d.type(), d));
        if (body != null && body.documents() != null) {
            for (DevDocument d : body.documents()) {
                ApplicationWriter.NewDocument nd = document(d);
                docs.put(nd.type(), nd);
            }
        }
        List<ApplicationWriter.NewDocument> copies = new ArrayList<>();
        docs.values().forEach(d -> copies.add(new ApplicationWriter.NewDocument(d.type(), 1, true, d.fileName(), d.totalPages(), d.sections())));
        long id;
        try {
            id = writer.addVersionCopying(loanApplicationId, copies); // the SSN token is copied in SQL, never read (FR-13)
        } catch (ResubmissionRefusedException e) {
            throw new ApiProblem(HttpStatus.CONFLICT, e.reason(), e.getMessage()); // C-24: never a second payment
        }
        VersionRow created = versions.find(id).orElseThrow();
        audit.record("APPLICATION_VERSION", Long.toString(id), "CREATED", actor(actor, "dev:versions"),
                Map.of("latestVersion", previous.applicationVersionId()),
                Map.of("versionNumber", created.versionNumber(), "status", "DRAFT", "replaces", previous.applicationVersionId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("loanApplicationId", loanApplicationId,
                "applicationVersionId", id, "versionNumber", created.versionNumber(), "status", "DRAFT",
                "previousVersionId", previous.applicationVersionId()));
    }

    @PostMapping("/{loanApplicationId}/documents")
    @Transactional
    public ResponseEntity<Map<String, Object>> addDocument(@PathVariable String loanApplicationId, @RequestBody DevDocument body) {
        VersionRow v = lockLatest(loanApplicationId);
        ApplicationWriter.AddedDocument added = writer.addDocumentVersion(v.applicationVersionId(), document(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("loanApplicationId", loanApplicationId,
                "applicationVersionId", v.applicationVersionId(), "documentId", added.documentId(),
                "documentType", body.type(), "documentVersion", added.documentVersion()));
    }

    private VersionRow lockLatest(String loanApplicationId) {
        VersionRow v = versions.findLatest(loanApplicationId).orElseThrow(() ->
                new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "no application " + loanApplicationId));
        try {
            return versions.lockLatest(v.applicationVersionId());
        } catch (SupersededException e) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NOT_LATEST_VERSION", "a newer version of " + loanApplicationId + " was just created");
        }
    }

    private static ApplicationWriter.NewDocument document(DevDocument d) {
        if (d == null || d.type() == null || !TYPES.contains(d.type()) || d.fileName() == null || d.totalPages() < 1 || d.sections() == null) {
            throw new IllegalArgumentException("a document needs type (one of " + TYPES + "), fileName, totalPages >= 1 and sections");
        }
        return new ApplicationWriter.NewDocument(d.type(), 0, true, d.fileName(), d.totalPages(), d.sections().stream()
                .map(s -> new ApplicationWriter.NewSection(s.name(), s.startPage(), s.endPage(), s.fields().stream()
                        .map(f -> new ApplicationWriter.NewField(f.key(), f.value(), f.confidence(), f.boundingBox(), f.page())).toList()))
                .toList());
    }

    private static String actor(String header, String fallback) {
        return header == null || header.isBlank() ? fallback : header;
    }
}
