package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/url")
    public ResponseEntity<Map<String, Object>> auditByUrl(@RequestParam("value") String url) throws IOException {
        return ResponseEntity.ok(auditService.auditByUrl(url));
    }
}

