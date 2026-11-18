package com.funding.api.funder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.api.ApiProblem;
import com.funding.domain.Check;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Funder operations API (US-10; plan "Funder &amp; Dev API"). Every write names its actor in
 * {@code X-Funder-Id} (AS-10.9, a stand-in until auth exists); errors are RFC 9457 problem+json.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Funder", description = "Funder operations on loan application versions (US-10)")
public class FunderController {

    static final String FUNDER_HEADER = "X-Funder-Id";
    private static final Set<String> JSON_COLUMNS = Set.of("details", "bounding_box", "before_state", "after_state");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FunderService funder;

    public FunderController(FunderService funder) {
        this.funder = funder;
    }

    public record ResolveStipRequest(String status, String reason) {}
    public record ManualStipRequest(String code, String description) {}
    public record AssigneeRequest(String funderId) {}
    public record RejectRequest(String reason) {}
    public record RerunRequest(List<Check> checks) {}

    // ------------------------------------------------------------------ reads

    @GetMapping("/applications/{loanApplicationId}")
    @Operation(summary = "Application overview (latest version)", description = "AS-10.1")
    public Map<String, Object> overview(@PathVariable String loanApplicationId) {
        return funder.overview(loanApplicationId);
    }

    @GetMapping("/applications/{loanApplicationId}/versions")
    @Operation(summary = "Version history, newest first", description = "US-9")
    public List<Map<String, Object>> versions(@PathVariable String loanApplicationId) {
        return rows(funder.versions(loanApplicationId));
    }

    @GetMapping("/versions/{versionId}/stipulations")
    @Operation(summary = "Stipulations of a version", description = "AS-10.2")
    public List<Map<String, Object>> stips(@PathVariable long versionId) {
        return rows(funder.stips(versionId));
    }

    @GetMapping("/versions/{versionId}/validation-results")
    @Operation(summary = "Validation results with expected vs actual", description = "AS-10.4; history=true includes earlier attempts")
    public List<Map<String, Object>> results(@PathVariable long versionId, @RequestParam(defaultValue = "false") boolean history) {
        return rows(funder.results(versionId, history));
    }

    @GetMapping("/versions/{versionId}/documents")
    @Operation(summary = "Document versions per type", description = "AS-10.5")
    public List<Map<String, Object>> documents(@PathVariable long versionId) {
        return rows(funder.documents(versionId));
    }

    @GetMapping("/versions/{versionId}/documents/{documentId}/fields")
    @Operation(summary = "Extracted fields with confidence, page and bounding box", description = "AS-10.5")
    public List<Map<String, Object>> fields(@PathVariable long versionId, @PathVariable long documentId) {
        return rows(funder.fields(versionId, documentId));
    }

    @GetMapping("/versions/{versionId}/audit")
    @Operation(summary = "Audit trail of the version and its stips", description = "FR-10")
    public List<Map<String, Object>> audit(@PathVariable long versionId) {
        return rows(funder.audit(versionId));
    }

    // ------------------------------------------------------------------ writes

    @PostMapping("/versions/{versionId}/stipulations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a manual stip", description = "AS-10.8: validation_check = NULL; moves PENDING_REVIEW or APPROVED to STIPULATED")
    public Map<String, Object> addStip(@PathVariable long versionId, @RequestBody ManualStipRequest body, @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.addManualStip(versionId, body.code(), body.description(), actor(actor));
    }

    @PatchMapping("/versions/{versionId}/stipulations/{stipId}")
    @Operation(summary = "Resolve a stip (SATISFIED or WAIVED)", description = "AS-10.2; RISC stips can't be waived (AS-8.10)")
    public Map<String, Object> resolveStip(@PathVariable long versionId, @PathVariable long stipId, @RequestBody ResolveStipRequest body,
                                           @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.resolveStip(versionId, stipId, body.status(), body.reason(), actor(actor));
    }

    @PutMapping("/versions/{versionId}/assignee")
    @Operation(summary = "Reassign the funder", description = "AS-10.3")
    public Map<String, Object> reassign(@PathVariable long versionId, @RequestBody AssigneeRequest body,
                                        @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.reassign(versionId, body.funderId(), actor(actor));
    }

    @PostMapping("/versions/{versionId}/approve")
    @Operation(summary = "Manual approve", description = "AS-8.7, AS-8.10: no PENDING stips, complete RISC, TILA PASS")
    public Map<String, Object> approve(@PathVariable long versionId, @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.approve(versionId, actor(actor));
    }

    @PostMapping("/versions/{versionId}/reject")
    @Operation(summary = "Reject", description = "AS-8.5")
    public Map<String, Object> reject(@PathVariable long versionId, @RequestBody RejectRequest body,
                                      @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.reject(versionId, body.reason(), actor(actor));
    }

    @PostMapping("/versions/{versionId}/rerun")
    @Operation(summary = "Re-run checks", description = "AS-8.4: the named checks, or by default those in ERROR")
    public Map<String, Object> rerun(@PathVariable long versionId, @RequestBody(required = false) RerunRequest body,
                                     @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.rerun(versionId, body == null ? null : body.checks(), actor(actor));
    }

    @PostMapping("/versions/{versionId}/retry-disbursement")
    @Operation(summary = "Retry a failed disbursement", description = "C-8")
    public Map<String, Object> retryDisbursement(@PathVariable long versionId,
                                                 @FunderId @RequestHeader(value = FUNDER_HEADER, required = false) String actor) {
        return funder.retryDisbursement(versionId, actor(actor));
    }

    // ------------------------------------------------------------------ helpers

    /** Documents the actor header in the OpenAPI output as required (it's checked here, to answer with problem+json). */
    @Parameter(name = FUNDER_HEADER, description = "Acting funder (AS-10.9)", required = true)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.PARAMETER})
    @interface FunderId {}

    private static String actor(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "MISSING_FUNDER_ID", FUNDER_HEADER + " header is required on every write (AS-10.9)");
        }
        return header.trim();
    }

    /** snake_case columns to camelCase keys; JSONB columns (returned as text) parsed back into JSON. */
    static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        return rows.stream().map(FunderController::row).toList();
    }

    private static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        r.forEach((k, v) -> {
            Object value = v;
            if (v instanceof String s && JSON_COLUMNS.contains(k)) {
                try {
                    value = JSON.readTree(s);
                } catch (JsonProcessingException e) {
                    value = s;
                }
            }
            out.put(camel(k), value);
        });
        return out;
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }
}
