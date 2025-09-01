package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.Citation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {
    private final ChatClient chatClient;
    private final RetrievalService retrievalService;

    public ChatService(ChatClient chatClient, RetrievalService retrievalService) {
        this.chatClient = chatClient;
        this.retrievalService = retrievalService;
    }

    public Flux<String> streamAnswer(List<Message> history, String latestUserMessage) {
        List<Document> contextDocs = retrievalService.retrieve(latestUserMessage);
        StringBuilder systemContext = new StringBuilder("You are a Java 24 documentation assistant. Answer using the provided context. Cite sources as [n] with URL.");
        for (int i = 0; i < contextDocs.size(); i++) {
            Document d = contextDocs.get(i);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(d.getMetadata().get("url")).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));

        return chatClient.prompt().messages(messages.toArray(new Message[0])).stream().content();
    }

    public List<Citation> citationsFor(String userQuery) {
        List<Document> docs = retrievalService.retrieve(userQuery);
        return retrievalService.toCitations(docs);
    }
}


