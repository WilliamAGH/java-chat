package com.williamcallahan.javachat.adapters.in.web;

import com.williamcallahan.javachat.support.StructuredLogValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Retains browser-observed search failures that cannot be reconstructed from origin HTTP statuses. */
@RestController
public final class BrowserSearchFailureController {
    private static final Logger log = LoggerFactory.getLogger(BrowserSearchFailureController.class);
    private static final int MAX_DIAGNOSTIC_FIELD_LENGTH = 160;

    /** Records a CSRF-protected, explicitly untrusted observation without accepting prompts or credentials. */
    @PostMapping("/api/diagnostics/search-failures")
    public ResponseEntity<Void> recordFailure(@Valid @RequestBody SearchFailureReport failureReport) {
        log.atWarn()
                .setMessage("Browser reported a search failure")
                .addKeyValue("event", "browser_search_failed")
                .addKeyValue("provenance", "client_reported")
                .addKeyValue("path", failureReport.path())
                .addKeyValue("kind", failureReport.kind())
                .addKeyValue("httpStatus", failureReport.httpStatus())
                .addKeyValue(
                        "requestId",
                        StructuredLogValue.bounded(failureReport.requestId(), MAX_DIAGNOSTIC_FIELD_LENGTH)
                                .text())
                .addKeyValue(
                        "code",
                        StructuredLogValue.bounded(failureReport.code(), MAX_DIAGNOSTIC_FIELD_LENGTH)
                                .text())
                .addKeyValue(
                        "stage",
                        StructuredLogValue.bounded(failureReport.stage(), MAX_DIAGNOSTIC_FIELD_LENGTH)
                                .text())
                .addKeyValue("retryable", failureReport.retryable())
                .addKeyValue("retryAfterSeconds", failureReport.retryAfterSeconds())
                .addKeyValue("pagePath", failureReport.pagePath())
                .addKeyValue("frontendAsset", failureReport.frontendAsset())
                .addKeyValue("elapsedMs", failureReport.elapsedMs())
                .log();
        return ResponseEntity.accepted().build();
    }

    /** Distinguishes a received refusal from an SSE failure or a browser transport/protocol failure. */
    public enum FailureKind {
        HTTP,
        SSE,
        NETWORK,
        PROTOCOL
    }

    /** Defines only the bounded metadata the browser is allowed to send into application logs. */
    public record SearchFailureReport(
            @NotBlank @Size(max = 160) @Pattern(regexp = "^/api/[^?#\\r\\n]*$")
            String path,

            @NotNull FailureKind kind,
            @Min(100) @Max(599) Integer httpStatus,
            @Size(max = 128) String requestId,
            @Size(max = 128) String code,
            @Size(max = 64) String stage,
            Boolean retryable,
            @PositiveOrZero Double retryAfterSeconds,

            @NotBlank @Size(max = 160) @Pattern(regexp = "^/[^?#\\r\\n]*$")
            String pagePath,

            @Size(max = 160) @Pattern(regexp = "^/[^?#\\r\\n]*$")
            String frontendAsset,

            @PositiveOrZero long elapsedMs) {}
}
