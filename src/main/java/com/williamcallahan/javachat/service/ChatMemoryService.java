package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.ChatTurn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // Use synchronizedList wrapper to ensure thread-safe list operations.
    // ConcurrentHashMap only protects map operations, not the contained lists.
    private final ConcurrentMap<String, List<Message>> sessionToMessages =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ChatTurn>> sessionToTurns =
        new ConcurrentHashMap<>();

    /**
     * Returns a thread-safe snapshot of the history for the given session.
     * Callers receive an independent copy that can be safely iterated without synchronization.
     */
    public List<Message> getHistory(String sessionId) {
        List<Message> history = sessionToMessages.computeIfAbsent(sessionId, k ->
            Collections.synchronizedList(new ArrayList<>())
        );
        // Return a snapshot to avoid ConcurrentModificationException during iteration
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /**
     * Returns the internal synchronized list for direct modification.
     * Use with care - prefer addUser/addAssistant for adding messages.
     */
    List<Message> getHistoryInternal(String sessionId) {
        return sessionToMessages.computeIfAbsent(sessionId, k ->
            Collections.synchronizedList(new ArrayList<>())
        );
    }

    public void addUser(String sessionId, String text) {
        getHistoryInternal(sessionId).add(new UserMessage(text));
        getTurnsInternal(sessionId).add(new ChatTurn("user", text));
    }

    public void addAssistant(String sessionId, String text) {
        getHistoryInternal(sessionId).add(new AssistantMessage(text));
        getTurnsInternal(sessionId).add(new ChatTurn("assistant", text));
    }

    public void clear(String sessionId) {
        sessionToMessages.remove(sessionId);
        sessionToTurns.remove(sessionId);
    }

    /**
     * Returns a thread-safe snapshot of the turns for the given session.
     */
    public List<ChatTurn> getTurns(String sessionId) {
        List<ChatTurn> turns = sessionToTurns.computeIfAbsent(sessionId, k ->
            Collections.synchronizedList(new ArrayList<>())
        );
        synchronized (turns) {
            return new ArrayList<>(turns);
        }
    }

    /**
     * Returns the internal synchronized list for direct modification.
     */
    List<ChatTurn> getTurnsInternal(String sessionId) {
        return sessionToTurns.computeIfAbsent(sessionId, k ->
            Collections.synchronizedList(new ArrayList<>())
        );
    }

    // TODO: Persist chat history embeddings to Qdrant for long-term memory (future feature)
}
