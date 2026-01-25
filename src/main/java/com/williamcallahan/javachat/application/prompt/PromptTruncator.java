package com.williamcallahan.javachat.application.prompt;

import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.ConversationTurnSegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
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
 *   <li>Remove oldest context documents first (LOW priority)</li>
 *   <li>Remove oldest conversation turns (MEDIUM priority)</li>
 *   <li>Never truncate system prompt or current query (CRITICAL/HIGH priority)</li>
 * </ol>
 *
 * <p>This ensures the user's question and system instructions are always preserved,
 * while older context and history are trimmed when necessary.</p>
 */
@Component
public class PromptTruncator {

    private static final Logger log = LoggerFactory.getLogger(PromptTruncator.class);

    /** Truncation notice for GPT-5 family models with 8K input limit. */
    private static final String TRUNCATION_NOTICE_GPT5 = "[Context truncated due to GPT-5 8K input limit]\n\n";

    /** Truncation notice for other models with larger limits. */
    private static final String TRUNCATION_NOTICE_GENERIC = "[Context truncated due to model input limit]\n\n";

    /**
     * Truncates a structured prompt to fit within the specified token limit.
     *
     * <p>Removes segments from lowest to highest priority until the prompt fits.
     * Context documents are removed first (oldest first), then conversation history
     * (oldest first). System prompt and current query are never removed.</p>
     *
     * @param prompt the structured prompt to truncate
     * @param maxTokens maximum allowed tokens
     * @param isGpt5Family true if targeting GPT-5 family models (affects notice text)
     * @return truncation result with the fitted prompt and truncation metadata
     */
    public TruncatedPrompt truncate(StructuredPrompt prompt, int maxTokens, boolean isGpt5Family) {
        int reservedTokens =
                prompt.system().estimatedTokens() + prompt.currentQuery().estimatedTokens();

        if (reservedTokens >= maxTokens) {
            log.warn(
                    "System prompt ({} tokens) + query ({} tokens) exceed limit ({} tokens)",
                    prompt.system().estimatedTokens(),
                    prompt.currentQuery().estimatedTokens(),
                    maxTokens);
            // Return prompt with only system and query - no room for context or history
            StructuredPrompt minimalPrompt =
                    new StructuredPrompt(prompt.system(), List.of(), List.of(), prompt.currentQuery());
            return new TruncatedPrompt(minimalPrompt, true, isGpt5Family);
        }

        int available = maxTokens - reservedTokens;
        boolean wasTruncated = false;
        int originalDocCount = prompt.contextDocuments().size();
        int originalTurnCount = prompt.conversationHistory().size();

        // Fit conversation history (newest first - reverse to prioritize recent)
        List<ConversationTurnSegment> fittingTurns = fitSegmentsNewestFirst(prompt.conversationHistory(), available);
        int turnsTokens = sumTokens(fittingTurns);
        available -= turnsTokens;

        if (fittingTurns.size() < prompt.conversationHistory().size()) {
            wasTruncated = true;
            log.debug("Truncated conversation history from {} to {} turns", originalTurnCount, fittingTurns.size());
        }

        // Fit context documents with remaining budget (most relevant first)
        List<ContextDocumentSegment> fittingDocs = fitDocumentsByRelevance(prompt.contextDocuments(), available);

        if (fittingDocs.size() < prompt.contextDocuments().size()) {
            wasTruncated = true;
            log.debug("Truncated context documents from {} to {}", originalDocCount, fittingDocs.size());
        }

        StructuredPrompt truncated =
                new StructuredPrompt(prompt.system(), fittingDocs, fittingTurns, prompt.currentQuery());

        if (wasTruncated) {
            log.info(
                    "Prompt truncated: {} docs → {}, {} turns → {} (limit: {} tokens)",
                    originalDocCount,
                    fittingDocs.size(),
                    originalTurnCount,
                    fittingTurns.size(),
                    maxTokens);
        }

        return new TruncatedPrompt(truncated, wasTruncated, isGpt5Family);
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
    private List<ContextDocumentSegment> fitDocumentsByRelevance(
            List<ContextDocumentSegment> docs, int availableTokens) {

        if (docs.isEmpty()) {
            return List.of();
        }

        // Process from first (most relevant) to last, keeping documents that fit
        List<ContextDocumentSegment> fitting = new ArrayList<>();
        int usedTokens = 0;

        for (ContextDocumentSegment doc : docs) {
            if (usedTokens + doc.estimatedTokens() <= availableTokens) {
                fitting.add(doc);
                usedTokens += doc.estimatedTokens();
            } else {
                break;
            }
        }

        // Re-index sequentially to maintain [CTX N] markers
        List<ContextDocumentSegment> reindexed = new ArrayList<>();
        for (int newIndex = 0; newIndex < fitting.size(); newIndex++) {
            ContextDocumentSegment original = fitting.get(newIndex);
            reindexed.add(new ContextDocumentSegment(
                    newIndex + 1, original.sourceUrl(), original.documentContent(), original.estimatedTokens()));
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
     * @param isGpt5Family true if targeting GPT-5 family models
     */
    public record TruncatedPrompt(StructuredPrompt prompt, boolean wasTruncated, boolean isGpt5Family) {
        /**
         * Renders the prompt to a string, prepending truncation notice if needed.
         *
         * @return final prompt string ready for LLM submission
         */
        public String render() {
            if (!wasTruncated) {
                return prompt.render();
            }
            String notice = isGpt5Family ? TRUNCATION_NOTICE_GPT5 : TRUNCATION_NOTICE_GENERIC;
            return notice + prompt.render();
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
}
