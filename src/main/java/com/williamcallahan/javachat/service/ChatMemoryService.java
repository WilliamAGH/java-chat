package com.williamcallahan.javachat.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import com.williamcallahan.javachat.model.ChatTurn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ChatMemoryService {
    private final ConcurrentMap<String, List<Message>> sessionToMessages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ChatTurn>> sessionToTurns = new ConcurrentHashMap<>();

    public List<Message> getHistory(String sessionId) {
        return sessionToMessages.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    public void addUser(String sessionId, String text) {
        getHistory(sessionId).add(new UserMessage(text));
        sessionToTurns.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new ChatTurn("user", text));
    }

    public void addAssistant(String sessionId, String text) {
        getHistory(sessionId).add(new AssistantMessage(text));
        sessionToTurns.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(new ChatTurn("assistant", text));
    }

    public void clear(String sessionId) {
        sessionToMessages.remove(sessionId);
        sessionToTurns.remove(sessionId);
    }

    public List<ChatTurn> getTurns(String sessionId) {
        return sessionToTurns.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    // TODO: Persist chat history embeddings to Qdrant for long-term memory (future feature)
}


