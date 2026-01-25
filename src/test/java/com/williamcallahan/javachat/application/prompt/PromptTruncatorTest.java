package com.williamcallahan.javachat.application.prompt;

import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.ConversationTurnSegment;
import com.williamcallahan.javachat.domain.prompt.CurrentQuerySegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.prompt.SystemSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies structure-aware prompt truncation preserves semantic boundaries.
 */
class PromptTruncatorTest {

    private PromptTruncator truncator;

    @BeforeEach
    void setUp() {
        truncator = new PromptTruncator();
    }

    @Test
    void noTruncationWhenWithinLimit() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System instructions", 50),
                List.of(new ContextDocumentSegment(1, "url1", "content1", 100)),
                List.of(new ConversationTurnSegment("user", "Hello", 10)),
                new CurrentQuerySegment("What is Java?", 20)
        );

        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 500, false);

        assertFalse(result.wasTruncated());
        assertEquals(1, result.contextDocumentCount());
        assertEquals(1, result.conversationTurnCount());
    }

    @Test
    void truncatesContextDocumentsFirst() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 100),
                List.of(
                        new ContextDocumentSegment(1, "url1", "doc1", 200),
                        new ContextDocumentSegment(2, "url2", "doc2", 200),
                        new ContextDocumentSegment(3, "url3", "doc3", 200)
                ),
                List.of(new ConversationTurnSegment("user", "history", 50)),
                new CurrentQuerySegment("query", 50)
        );

        // Total: 100 + 600 + 50 + 50 = 800 tokens
        // Limit 350: 100 (system) + 50 (query) = 150 reserved, 200 available
        // Should fit 1 context doc (200) but not 2, plus conversation (50)
        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 400, false);

        assertTrue(result.wasTruncated());
        // With 400 limit: 100 + 50 reserved = 150, 250 available
        // Fit history (50) = 200 left, fit 1 doc (200) = 0 left
        assertEquals(1, result.contextDocumentCount());
        assertEquals(1, result.conversationTurnCount());
    }

    @Test
    void truncatesOldConversationHistoryFirst() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 100),
                List.of(),
                List.of(
                        new ConversationTurnSegment("user", "old1", 100),
                        new ConversationTurnSegment("assistant", "old2", 100),
                        new ConversationTurnSegment("user", "recent", 100)
                ),
                new CurrentQuerySegment("query", 50)
        );

        // Total: 100 + 0 + 300 + 50 = 450 tokens
        // Limit 300: 100 + 50 = 150 reserved, 150 available
        // Should fit 1 turn (100) from newest
        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 300, false);

        assertTrue(result.wasTruncated());
        assertEquals(0, result.contextDocumentCount());
        assertEquals(1, result.conversationTurnCount());
        // Verify it's the most recent turn
        String rendered = result.render();
        assertTrue(rendered.contains("recent"));
        assertFalse(rendered.contains("old1"));
    }

    @Test
    void neverTruncatesSystemPromptOrCurrentQuery() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("Critical system instructions", 200),
                List.of(new ContextDocumentSegment(1, "url", "doc", 100)),
                List.of(new ConversationTurnSegment("user", "history", 100)),
                new CurrentQuerySegment("Important question", 200)
        );

        // Limit smaller than system + query alone
        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 350, true);

        assertTrue(result.wasTruncated());
        assertEquals(0, result.contextDocumentCount());
        assertEquals(0, result.conversationTurnCount());

        // System and query must be present
        String rendered = result.render();
        assertTrue(rendered.contains("Critical system instructions"));
        assertTrue(rendered.contains("Important question"));
    }

    @Test
    void prependsTruncationNoticeForGpt5() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 100),
                List.of(
                        new ContextDocumentSegment(1, "url1", "doc1", 500),
                        new ContextDocumentSegment(2, "url2", "doc2", 500)
                ),
                List.of(),
                new CurrentQuerySegment("query", 50)
        );

        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 400, true);

        assertTrue(result.wasTruncated());
        assertTrue(result.render().startsWith("[Context truncated due to GPT-5"));
    }

    @Test
    void prependsGenericTruncationNoticeForOtherModels() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 100),
                List.of(new ContextDocumentSegment(1, "url1", "doc1", 500)),
                List.of(),
                new CurrentQuerySegment("query", 50)
        );

        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 200, false);

        assertTrue(result.wasTruncated());
        assertTrue(result.render().startsWith("[Context truncated due to model input limit]"));
    }

    @Test
    void reindexesContextDocumentsAfterTruncation() {
        // Documents ordered by relevance (most relevant first, as from reranker)
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 50),
                List.of(
                        new ContextDocumentSegment(1, "url1", "doc1", 100),
                        new ContextDocumentSegment(2, "url2", "doc2", 100),
                        new ContextDocumentSegment(3, "url3", "doc3", 100)
                ),
                List.of(),
                new CurrentQuerySegment("query", 50)
        );

        // Should keep first 2 docs (most relevant), re-indexed as 1 and 2
        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 350, false);

        assertTrue(result.wasTruncated());
        assertEquals(2, result.contextDocumentCount());

        String rendered = result.render();
        // Should have [CTX 1] and [CTX 2], not [CTX 3]
        assertTrue(rendered.contains("[CTX 1]"));
        assertTrue(rendered.contains("[CTX 2]"));
        assertFalse(rendered.contains("[CTX 3]"));
        // Should contain content from original docs 1 and 2 (most relevant)
        assertTrue(rendered.contains("url1") && rendered.contains("url2"));
        assertFalse(rendered.contains("url3"));
    }

    @Test
    void handlesEmptyContextAndHistory() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 100),
                List.of(),
                List.of(),
                new CurrentQuerySegment("query", 50)
        );

        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 200, false);

        assertFalse(result.wasTruncated());
        assertEquals(0, result.contextDocumentCount());
        assertEquals(0, result.conversationTurnCount());

        String rendered = result.render();
        assertTrue(rendered.contains("System"));
        assertTrue(rendered.contains("query"));
    }

    @Test
    void preservesAssistantPrefixInRenderedOutput() {
        StructuredPrompt prompt = new StructuredPrompt(
                new SystemSegment("System", 50),
                List.of(),
                List.of(
                        new ConversationTurnSegment("user", "question", 20),
                        new ConversationTurnSegment("assistant", "answer", 20)
                ),
                new CurrentQuerySegment("follow-up", 20)
        );

        PromptTruncator.TruncatedPrompt result = truncator.truncate(prompt, 500, false);

        assertFalse(result.wasTruncated());
        String rendered = result.render();
        assertTrue(rendered.contains("Assistant: answer"));
        assertFalse(rendered.contains("User:")); // User messages have no prefix
    }
}
