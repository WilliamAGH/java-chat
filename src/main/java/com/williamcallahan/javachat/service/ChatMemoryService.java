package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.ChatTurn;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * Thread-safe service for managing chat history per session.
 *
 * Uses synchronized lists to prevent ConcurrentModificationException and lost updates
 * when multiple threads access the same session's history simultaneously.
 */
@Service
public class ChatMemoryService {

    private static final String REQUIRE_SESSION_ID = "sessionId";

    private final ConcurrentMap<String, SessionConversation> sessionConversations = new ConcurrentHashMap<>();

    /**
     * Returns a thread-safe snapshot of the history for the given session.
     * Callers receive an independent copy that can be safely iterated without synchronization.
     */
    public List<Message> getHistory(String sessionId) {
        Objects.requireNonNull(sessionId, REQUIRE_SESSION_ID);
        SessionConversation sessionConversation = sessionConversations.get(sessionId);
        if (sessionConversation == null) {
            return List.of();
        }
        return sessionConversation.historySnapshot();
    }

    /**
     * Adds a user message to the session history and turn list.
     *
     * @param sessionId session identifier
     * @param text message text
     */
    public void addUser(String sessionId, String text) {
        Objects.requireNonNull(sessionId, REQUIRE_SESSION_ID);
        SessionConversation sessionConversation =
                sessionConversations.computeIfAbsent(sessionId, _ -> new SessionConversation());
        sessionConversation.addUserMessage(text);
    }

    /**
     * Adds an assistant message to the session history and turn list.
     *
     * @param sessionId session identifier
     * @param text message text
     */
    public void addAssistant(String sessionId, String text) {
        Objects.requireNonNull(sessionId, REQUIRE_SESSION_ID);
        SessionConversation sessionConversation =
                sessionConversations.computeIfAbsent(sessionId, _ -> new SessionConversation());
        sessionConversation.addAssistantMessage(text);
    }

    /**
     * Clears all stored history for the provided session.
     *
     * @param sessionId session identifier
     */
    public void clear(String sessionId) {
        Objects.requireNonNull(sessionId, REQUIRE_SESSION_ID);
        sessionConversations.remove(sessionId);
    }

    /**
     * Returns a thread-safe snapshot of the turns for the given session.
     */
    public List<ChatTurn> getTurns(String sessionId) {
        Objects.requireNonNull(sessionId, REQUIRE_SESSION_ID);
        SessionConversation sessionConversation = sessionConversations.get(sessionId);
        if (sessionConversation == null) {
            return List.of();
        }
        return sessionConversation.turnSnapshot();
    }

    /**
     * Returns true when the server currently recognizes the given session identifier.
     *
     * @param sessionId session identifier
     * @return true when the session has been created in memory
     */
    public boolean hasSession(String sessionId) {
        Objects.requireNonNull(sessionId, REQUIRE_SESSION_ID);
        return sessionConversations.containsKey(sessionId);
    }

    // TODO: Persist chat history embeddings to Qdrant for long-term memory (future feature)
    //
    // This feature would enable:
    // - Persistent chat sessions across application restarts
    // - Historical context retrieval for better conversation continuity
    // - User-specific chat memory with configurable retention policies
    // Implementation should consider:
    // - Embedding strategy for chat turns (user messages + AI responses)
    // - Semantic similarity search for relevant historical context
    // - Privacy and data retention compliance

    /**
     * Holds per-session conversation state and synchronizes updates across message and turn views.
     */
    private static final class SessionConversation {
        private final List<Message> historyMessages = new ArrayList<>();
        private final List<ChatTurn> turnHistory = new ArrayList<>();

        synchronized void addUserMessage(String text) {
            historyMessages.add(new UserMessage(text));
            turnHistory.add(new ChatTurn("user", text));
        }

        synchronized void addAssistantMessage(String text) {
            historyMessages.add(new AssistantMessage(text));
            turnHistory.add(new ChatTurn("assistant", text));
        }

        synchronized List<Message> historySnapshot() {
            return List.copyOf(historyMessages);
        }

        synchronized List<ChatTurn> turnSnapshot() {
            return List.copyOf(turnHistory);
        }
    }
}
