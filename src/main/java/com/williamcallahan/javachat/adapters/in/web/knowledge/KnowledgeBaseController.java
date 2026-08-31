package com.williamcallahan.javachat.adapters.in.web.knowledge;

import com.williamcallahan.javachat.application.knowledge.KnowledgeBaseInventoryUseCase;
import com.williamcallahan.javachat.application.knowledge.KnowledgeInventoryUnavailableException;
import com.williamcallahan.javachat.domain.errors.ApiErrorResponse;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeInventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the authenticated, complete knowledge-base inventory. */
@RestController
@RequestMapping("/api/knowledge")
public final class KnowledgeBaseController {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseInventoryUseCase knowledgeBaseInventoryUseCase;

    /** Wires the complete inventory use case to the authenticated HTTP boundary. */
    public KnowledgeBaseController(KnowledgeBaseInventoryUseCase knowledgeBaseInventoryUseCase) {
        this.knowledgeBaseInventoryUseCase = knowledgeBaseInventoryUseCase;
    }

    /** Returns current groups and their authoritative total chunk count. */
    @GetMapping("/groups")
    public KnowledgeInventory listKnowledgeGroups() {
        return knowledgeBaseInventoryUseCase.listKnowledgeInventory();
    }

    /** Maps incomplete backing-store reads to the endpoint's documented gateway failure. */
    @ExceptionHandler(KnowledgeInventoryUnavailableException.class)
    ResponseEntity<ApiErrorResponse> inventoryUnavailable(KnowledgeInventoryUnavailableException failure) {
        logger.warn("Knowledge inventory unavailable", failure);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorResponse.error("Knowledge inventory is temporarily unavailable."));
    }
}
