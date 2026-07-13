package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Validates chat memory and conversation context handling.
 * Ensures that what the user sees in the UI matches what the AI receives in context.
 */
class ChatMemoryServiceTest {

    private ChatMemoryService chatMemoryService;

    @BeforeEach
    void setUp() {
        chatMemoryService = new ChatMemoryService();
    }

    @Test
    @DisplayName("Should store completed exchanges")
    void shouldStoreCompletedExchanges() {
        String sessionId = "test-session";
        chatMemoryService.addExchange(sessionId, "What is Java?", "Java is a programming language.");

        List<Message> history = chatMemoryService.getHistory(sessionId);

        assertEquals(2, history.size());
        assertInstanceOf(UserMessage.class, history.get(0));
        assertEquals("What is Java?", ((UserMessage) history.get(0)).getText());
        assertInstanceOf(AssistantMessage.class, history.get(1));
        assertEquals("Java is a programming language.", ((AssistantMessage) history.get(1)).getText());
    }

    @Test
    @DisplayName("Should maintain conversation order with both user and assistant messages")
    void shouldMaintainConversationOrder() {
        String sessionId = "test-session";

        chatMemoryService.addExchange(sessionId, "What is Java?", "Java is a programming language.");
        chatMemoryService.addExchange(sessionId, "Give me an example.", "Here is a Hello World example...");

        List<Message> history = chatMemoryService.getHistory(sessionId);

        assertEquals(4, history.size());
        assertInstanceOf(UserMessage.class, history.get(0));
        assertInstanceOf(AssistantMessage.class, history.get(1));
        assertInstanceOf(UserMessage.class, history.get(2));
        assertInstanceOf(AssistantMessage.class, history.get(3));
    }

    @Test
    @DisplayName("Should return empty history for unknown session")
    void shouldReturnEmptyHistoryForUnknownSession() {
        List<Message> history = chatMemoryService.getHistory("nonexistent-session");

        assertNotNull(history);
        assertTrue(history.isEmpty());
        assertFalse(chatMemoryService.hasSession("nonexistent-session"));
    }

    @Test
    @DisplayName("Should clear session history")
    void shouldClearSessionHistory() {
        String sessionId = "test-session";
        chatMemoryService.addExchange(sessionId, "Test message", "Test response");

        chatMemoryService.clear(sessionId);

        List<Message> history = chatMemoryService.getHistory(sessionId);
        assertTrue(history.isEmpty());
        assertFalse(chatMemoryService.hasSession(sessionId));
    }

    @Test
    @DisplayName("Should isolate sessions from each other")
    void shouldIsolateSessions() {
        chatMemoryService.addExchange("session-1", "Message for session 1", "Response for session 1");
        chatMemoryService.addExchange("session-2", "Message for session 2", "Response for session 2");

        List<Message> history1 = chatMemoryService.getHistory("session-1");
        List<Message> history2 = chatMemoryService.getHistory("session-2");

        assertEquals(2, history1.size());
        assertEquals(2, history2.size());
        assertEquals("Message for session 1", ((UserMessage) history1.get(0)).getText());
        assertEquals("Message for session 2", ((UserMessage) history2.get(0)).getText());
    }

    @Test
    @DisplayName("Should return snapshot that is safe to iterate")
    void shouldReturnSnapshotSafeToIterate() {
        String sessionId = "test-session";
        chatMemoryService.addExchange(sessionId, "First message", "First response");

        List<Message> history = chatMemoryService.getHistory(sessionId);

        // Modify the original session after getting history
        chatMemoryService.addExchange(sessionId, "Second message", "Second response");

        // The snapshot should not be affected
        assertEquals(2, history.size());
    }

    @Test
    @DisplayName("Should track turns alongside messages")
    void shouldTrackTurnsAlongsideMessages() {
        String sessionId = "test-session";
        chatMemoryService.addExchange(sessionId, "User question", "Assistant answer");

        var turns = chatMemoryService.getTurns(sessionId);

        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).getRole());
        assertEquals("User question", turns.get(0).getText());
        assertEquals("assistant", turns.get(1).getRole());
        assertEquals("Assistant answer", turns.get(1).getText());
    }

    @Test
    @DisplayName("Should keep message history and turn history aligned under concurrent writes")
    void shouldKeepHistoryAndTurnsAlignedUnderConcurrency() throws InterruptedException {
        String sessionId = "concurrent-session";
        int workerCount = 40;
        ExecutorService workerPool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch completionSignal = new CountDownLatch(workerCount);

        for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
            final int messageIndex = workerIndex;
            workerPool.execute(() -> {
                try {
                    startSignal.await();
                    chatMemoryService.addExchange(sessionId, "message-" + messageIndex, "response-" + messageIndex);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        boolean completed = completionSignal.await(10, TimeUnit.SECONDS);
        workerPool.shutdownNow();
        assertTrue(completed, "Concurrent writes did not complete within timeout");

        List<Message> history = chatMemoryService.getHistory(sessionId);
        var turns = chatMemoryService.getTurns(sessionId);
        assertEquals(workerCount * 2, history.size());
        assertEquals(workerCount * 2, turns.size());
        for (int messageIndex = 0; messageIndex < workerCount * 2; messageIndex++) {
            String historyText = history.get(messageIndex).getText();
            String turnText = turns.get(messageIndex).getText();
            assertEquals(historyText, turnText);
        }
    }
}
