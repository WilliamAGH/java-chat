package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
    @DisplayName("Should store and retrieve user messages")
    void shouldStoreAndRetrieveUserMessages() {
        String sessionId = "test-session";
        chatMemoryService.addUser(sessionId, "What is Java?");

        List<Message> history = chatMemoryService.getHistory(sessionId);

        assertEquals(1, history.size());
        assertInstanceOf(UserMessage.class, history.get(0));
        assertEquals("What is Java?", ((UserMessage) history.get(0)).getText());
    }

    @Test
    @DisplayName("Should store and retrieve assistant messages")
    void shouldStoreAndRetrieveAssistantMessages() {
        String sessionId = "test-session";
        chatMemoryService.addAssistant(sessionId, "Java is a programming language.");

        List<Message> history = chatMemoryService.getHistory(sessionId);

        assertEquals(1, history.size());
        assertInstanceOf(AssistantMessage.class, history.get(0));
        assertEquals("Java is a programming language.", ((AssistantMessage) history.get(0)).getText());
    }

    @Test
    @DisplayName("Should maintain conversation order with both user and assistant messages")
    void shouldMaintainConversationOrder() {
        String sessionId = "test-session";

        chatMemoryService.addUser(sessionId, "What is Java?");
        chatMemoryService.addAssistant(sessionId, "Java is a programming language.");
        chatMemoryService.addUser(sessionId, "Give me an example.");
        chatMemoryService.addAssistant(sessionId, "Here is a Hello World example...");

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
    }

    @Test
    @DisplayName("Should clear session history")
    void shouldClearSessionHistory() {
        String sessionId = "test-session";
        chatMemoryService.addUser(sessionId, "Test message");
        chatMemoryService.addAssistant(sessionId, "Test response");

        chatMemoryService.clear(sessionId);

        List<Message> history = chatMemoryService.getHistory(sessionId);
        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("Should isolate sessions from each other")
    void shouldIsolateSessions() {
        chatMemoryService.addUser("session-1", "Message for session 1");
        chatMemoryService.addUser("session-2", "Message for session 2");

        List<Message> history1 = chatMemoryService.getHistory("session-1");
        List<Message> history2 = chatMemoryService.getHistory("session-2");

        assertEquals(1, history1.size());
        assertEquals(1, history2.size());
        assertEquals("Message for session 1", ((UserMessage) history1.get(0)).getText());
        assertEquals("Message for session 2", ((UserMessage) history2.get(0)).getText());
    }

    @Test
    @DisplayName("Should return snapshot that is safe to iterate")
    void shouldReturnSnapshotSafeToIterate() {
        String sessionId = "test-session";
        chatMemoryService.addUser(sessionId, "First message");

        List<Message> history = chatMemoryService.getHistory(sessionId);

        // Modify the original session after getting history
        chatMemoryService.addUser(sessionId, "Second message");

        // The snapshot should not be affected
        assertEquals(1, history.size());
    }

    @Test
    @DisplayName("Should track turns alongside messages")
    void shouldTrackTurnsAlongsideMessages() {
        String sessionId = "test-session";
        chatMemoryService.addUser(sessionId, "User question");
        chatMemoryService.addAssistant(sessionId, "Assistant answer");

        var turns = chatMemoryService.getTurns(sessionId);

        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).getRole());
        assertEquals("User question", turns.get(0).getText());
        assertEquals("assistant", turns.get(1).getRole());
        assertEquals("Assistant answer", turns.get(1).getText());
    }
}
