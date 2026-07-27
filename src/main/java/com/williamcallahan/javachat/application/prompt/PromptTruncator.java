package com.williamcallahan.javachat.application.prompt;

import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.ConversationTurnSegment;
import com.williamcallahan.javachat.domain.prompt.PromptSegmentPriority;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Truncates structured prompts to fit within model token limits while preserving semantic boundaries.
 *
 * <p>Truncation strategy prioritizes content by importance:
 * <ol>
 *   <li>Retain lesson-owned HIGH context before conversation history</li>
 *   <li>Retain newest conversation turns next (MEDIUM priority)</li>
 *   <li>Use remaining space for ordinary retrieval context (LOW priority)</li>
 *   <li>Never truncate system prompt or current query (CRITICAL/HIGH priority)</li>
 * </ol>
 *
 * <p>This ensures the user's question and system instructions are always preserved,
 * while older context and history are trimmed when necessary.</p>
 */
@Component
public class PromptTruncator {

    private static final Logger log = LoggerFactory.getLogger(PromptTruncator.class);

    private static final String TRUNCATION_NOTICE = "[Context truncated due to model input limit]\n\n";

    /**
     * Truncates a structured prompt to fit within the specified token limit.
     *
     * <p>Retains HIGH authoritative context first, then the newest contiguous conversation history,
     * then LOW retrieval context in relevance order. System prompt and current query are never removed.</p>
     *
     * @param prompt the structured prompt to truncate
     * @param maxTokens maximum allowed tokens
     * @return truncation result with the fitted prompt and truncation metadata
     * @throws AuthoritativeContextDoesNotFitException when no HIGH-priority context segment fits
     */
    public TruncatedPrompt truncate(StructuredPrompt prompt, int maxTokens) {
        int reservedTokens =
                prompt.system().estimatedTokens() + prompt.currentQuery().estimatedTokens();
        List<ContextDocumentSegment> authoritativeContextDocuments = prompt.contextDocuments().stream()
                .filter(contextDocument -> contextDocument.priority() == PromptSegmentPriority.HIGH)
                .toList();
        requireAuthoritativeContextFits(authoritativeContextDocuments, maxTokens, reservedTokens);

        if (reservedTokens >= maxTokens) {
            log.warn(
                    "System prompt ({} tokens) + query ({} tokens) exceed limit ({} tokens)",
                    prompt.system().estimatedTokens(),
                    prompt.currentQuery().estimatedTokens(),
                    maxTokens);
            // Return prompt with only system and query - no room for context or history
            StructuredPrompt minimalPrompt =
                    new StructuredPrompt(prompt.system(), List.of(), List.of(), prompt.currentQuery());
            return new TruncatedPrompt(minimalPrompt, true);
        }

        int available = maxTokens - reservedTokens;
        boolean wasTruncated = false;
        int originalDocCount = prompt.contextDocuments().size();
        int originalTurnCount = prompt.conversationHistory().size();

        List<ContextDocumentSegment> fittingHighPriorityDocuments =
                fitDocumentsByPriority(authoritativeContextDocuments, available, PromptSegmentPriority.HIGH);
        available -= sumTokens(fittingHighPriorityDocuments);

        // Fit conversation history (newest first - reverse to prioritize recent)
        List<ConversationTurnSegment> fittingTurns = fitSegmentsNewestFirst(prompt.conversationHistory(), available);
        int turnsTokens = sumTokens(fittingTurns);
        available -= turnsTokens;

        if (fittingTurns.size() < prompt.conversationHistory().size()) {
            wasTruncated = true;
            log.debug("Truncated conversation history from {} to {} turns", originalTurnCount, fittingTurns.size());
        }

        List<ContextDocumentSegment> fittingLowPriorityDocuments =
                fitDocumentsByPriority(prompt.contextDocuments(), available, PromptSegmentPriority.LOW);
        List<ContextDocumentSegment> fittingDocs =
                new ArrayList<>(fittingHighPriorityDocuments.size() + fittingLowPriorityDocuments.size());
        fittingDocs.addAll(fittingHighPriorityDocuments);
        fittingDocs.addAll(fittingLowPriorityDocuments);
        List<ContextDocumentSegment> reindexedDocuments = reindexDocuments(fittingDocs);

        if (reindexedDocuments.size() < prompt.contextDocuments().size()) {
            wasTruncated = true;
            log.debug("Truncated context documents from {} to {}", originalDocCount, fittingDocs.size());
        }

        StructuredPrompt truncated =
                new StructuredPrompt(prompt.system(), reindexedDocuments, fittingTurns, prompt.currentQuery());

        if (wasTruncated) {
            log.info(
                    "Prompt truncated: {} docs → {}, {} turns → {} (limit: {} tokens)",
                    originalDocCount,
                    fittingDocs.size(),
                    originalTurnCount,
                    fittingTurns.size(),
                    maxTokens);
        }

        return new TruncatedPrompt(truncated, wasTruncated);
    }

    /**
     * Fits conversation turns within token budget, prioritizing newest.
     */
    private List<ConversationTurnSegment> fitSegmentsNewestFirst(
            List<ConversationTurnSegment> turns, int availableTokens) {

        if (turns.isEmpty()) {
            return List.of();
        }

        // Process from newest to oldest
        List<ConversationTurnSegment> reversed = new ArrayList<>(turns);
        Collections.reverse(reversed);

        List<ConversationTurnSegment> fitting = new ArrayList<>();
        int usedTokens = 0;

        for (ConversationTurnSegment turn : reversed) {
            if (usedTokens + turn.estimatedTokens() <= availableTokens) {
                fitting.add(turn);
                usedTokens += turn.estimatedTokens();
            } else {
                break;
            }
        }

        // Restore chronological order
        Collections.reverse(fitting);
        return List.copyOf(fitting);
    }

    /**
     * Fits context documents within token budget, prioritizing most relevant first.
     *
     * <p>Documents are assumed to be ordered by relevance (most relevant first),
     * matching the output order from reranking. Documents that fit are kept in
     * their original order and re-indexed with sequential [CTX N] markers.</p>
     */
    private List<ContextDocumentSegment> fitDocumentsByPriority(
            List<ContextDocumentSegment> contextDocuments,
            int availableTokens,
            PromptSegmentPriority retentionPriority) {

        if (contextDocuments.isEmpty()) {
            return List.of();
        }

        // Process from first (most relevant) to last, keeping documents that fit
        List<ContextDocumentSegment> fitting = new ArrayList<>();
        int usedTokens = 0;

        for (ContextDocumentSegment contextDocument : contextDocuments) {
            if (contextDocument.priority() != retentionPriority) {
                continue;
            }
            if (usedTokens + contextDocument.estimatedTokens() <= availableTokens) {
                fitting.add(contextDocument);
                usedTokens += contextDocument.estimatedTokens();
            }
        }
        return List.copyOf(fitting);
    }

    private static void requireAuthoritativeContextFits(
            List<ContextDocumentSegment> authoritativeContextDocuments, int maxTokens, int reservedTokens) {
        if (authoritativeContextDocuments.isEmpty()) {
            return;
        }
        int availableTokens = maxTokens - reservedTokens;
        int smallestAuthoritativeSegmentTokens = authoritativeContextDocuments.stream()
                .mapToInt(ContextDocumentSegment::estimatedTokens)
                .min()
                .orElseThrow();
        if (smallestAuthoritativeSegmentTokens > availableTokens) {
            throw new AuthoritativeContextDoesNotFitException(
                    maxTokens, reservedTokens, smallestAuthoritativeSegmentTokens);
        }
    }

    private List<ContextDocumentSegment> reindexDocuments(List<ContextDocumentSegment> retainedDocuments) {
        List<ContextDocumentSegment> reindexed = new ArrayList<>();
        for (int newIndex = 0; newIndex < retainedDocuments.size(); newIndex++) {
            ContextDocumentSegment original = retainedDocuments.get(newIndex);
            reindexed.add(new ContextDocumentSegment(
                            newIndex + 1,
                            original.documentId(),
                            original.sourceUrl(),
                            original.documentContent(),
                            original.estimatedTokens())
                    .withPriority(original.priority()));
        }
        return List.copyOf(reindexed);
    }

    private int sumTokens(List<? extends com.williamcallahan.javachat.domain.prompt.PromptSegment> segments) {
        int total = 0;
        for (var segment : segments) {
            total += segment.estimatedTokens();
        }
        return total;
    }

    /**
     * Captures truncation outcome including the fitted prompt and metadata.
     *
     * @param prompt the truncated structured prompt
     * @param wasTruncated true if any segments were removed
     */
    public record TruncatedPrompt(StructuredPrompt prompt, boolean wasTruncated) {
        /**
         * Renders the complete prompt, prepending the truncation notice when needed.
         *
         * @return final complete prompt string
         */
        public String render() {
            return prependTruncationNotice(prompt.render());
        }

        /**
         * Renders non-system request input, prepending the truncation notice when needed.
         *
         * <p>The system segment remains available through {@link #prompt()} so the request
         * boundary can submit it as system-level instructions.</p>
         *
         * @return final non-system input string ready for LLM submission
         */
        public String renderInput() {
            return prependTruncationNotice(prompt.renderInput());
        }

        private String prependTruncationNotice(String renderedPrompt) {
            if (!wasTruncated) {
                return renderedPrompt;
            }
            return TRUNCATION_NOTICE + renderedPrompt;
        }

        /**
         * Returns the number of context documents in the truncated prompt.
         *
         * @return document count after truncation
         */
        public int contextDocumentCount() {
            return prompt.contextDocuments().size();
        }

        /**
         * Returns the number of conversation turns in the truncated prompt.
         *
         * @return turn count after truncation
         */
        public int conversationTurnCount() {
            return prompt.conversationHistory().size();
        }
    }

    /** Signals that a grounded prompt cannot retain any authoritative context segment. */
    public static final class AuthoritativeContextDoesNotFitException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;

        private AuthoritativeContextDoesNotFitException(
                int maxTokens, int reservedTokens, int smallestAuthoritativeSegmentTokens) {
            super("No authoritative context segment fits the prompt budget: maxTokens="
                    + maxTokens
                    + ", reservedTokens="
                    + reservedTokens
                    + ", smallestAuthoritativeSegmentTokens="
                    + smallestAuthoritativeSegmentTokens);
        }
    }
}
