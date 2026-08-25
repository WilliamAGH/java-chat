package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards token-aware chunking against content that quotes tokenizer special tokens.
 *
 * <p>Ingested documentation legitimately embeds strings such as {@code <|endoftext|>} inside code
 * samples (observed in the tinker cookbook tokenizer reference); strict encoding rejects them and
 * previously aborted the whole ingestion run.
 */
class ChunkerTest {
    private static final String SPECIAL_TOKEN_SAMPLE =
            "The tokenizer appends \"<|endoftext|>\" after each completion sequence.";

    private final Chunker chunker = new Chunker();

    @Test
    void chunkByTokensTreatsQuotedSpecialTokensAsPlainText() {
        List<String> chunks = chunker.chunkByTokens(SPECIAL_TOKEN_SAMPLE, 8, 0);

        assertTrue(chunks.size() > 1);
        assertEquals(SPECIAL_TOKEN_SAMPLE, String.join("", chunks));
    }

    @Test
    void countTokensAcceptsQuotedSpecialTokens() {
        assertTrue(chunker.countTokens(SPECIAL_TOKEN_SAMPLE) > 0);
    }

    @Test
    void keepLastTokensAcceptsQuotedSpecialTokens() {
        String truncated = chunker.keepLastTokens(SPECIAL_TOKEN_SAMPLE, 4);

        assertTrue(SPECIAL_TOKEN_SAMPLE.endsWith(truncated));
    }
}
