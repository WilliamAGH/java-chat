package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.AuditReport;
import com.williamcallahan.javachat.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REST controller for auditing ingested content against the vector store.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Audits a URL's ingested chunks against the vector store.
     *
     * @param url the URL to audit
     * @return audit report with expected vs actual chunk counts and any discrepancies
     * @throws IOException if local chunk files cannot be read
     */
    @GetMapping("/url")
    public ResponseEntity<AuditReport> auditByUrl(@RequestParam("value") String url) throws IOException {
        return ResponseEntity.ok(auditService.auditByUrl(url));
    }
}

