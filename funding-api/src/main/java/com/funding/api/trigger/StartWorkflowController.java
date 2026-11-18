package com.funding.api.trigger;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/funding-workflows} (called by the trigger Lambda) and {@code GET …/{vid}}. */
@RestController
@RequestMapping("/api/v1/funding-workflows")
public class StartWorkflowController {

    private final StartWorkflowService service;

    public StartWorkflowController(StartWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartWorkflowRequest request) {
        StartWorkflowService.Started s = service.start(request);
        if (s.alreadyStarted()) {
            return ResponseEntity.ok(Map.of("workflowId", s.workflowId(), "runId", s.runId(), "alreadyStarted", true));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("workflowId", s.workflowId(), "runId", s.runId()));
    }

    @GetMapping("/{applicationVersionId}")
    public Map<String, Object> get(@PathVariable long applicationVersionId) {
        return service.describe(applicationVersionId);
    }
}
