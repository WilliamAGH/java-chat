package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.KnowledgeGroup;
import com.williamcallahan.javachat.service.KnowledgeBaseInventoryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing what the knowledge base contains.
 *
 * <p>Answers the CLI's "what can I ask about?" question: every ingested document group
 * (documentation sets, book cohorts, article series, PDFs, and indexed GitHub repositories)
 * with its chunk count. Authentication is enforced at the security filter chain.</p>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {
    private final KnowledgeBaseInventoryService knowledgeBaseInventoryService;

    /**
     * Creates the controller backed by the knowledge-base inventory service.
     */
    public KnowledgeBaseController(KnowledgeBaseInventoryService knowledgeBaseInventoryService) {
        this.knowledgeBaseInventoryService = knowledgeBaseInventoryService;
    }

    /**
     * Lists the ingested document groups so clients can discover the available knowledge.
     *
     * @return every ingested document group with its chunk count
     */
    @GetMapping("/groups")
    public ResponseEntity<List<KnowledgeGroup>> listKnowledgeGroups() {
        return ResponseEntity.ok(knowledgeBaseInventoryService.listKnowledgeGroups());
    }
}
