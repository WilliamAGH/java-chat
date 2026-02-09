package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.service.ChatMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies chat session validation semantics for unknown and recognized sessions.
 */
class ChatControllerSessionValidationTest {

    @Test
    void validateSession_doesNotCreateUnknownSessionAndReportsRecognizedSessionHistory() {
        ChatMemoryService chatMemoryService = new ChatMemoryService();
        ChatController chatController = new ChatController(
                null, chatMemoryService, null, null, null, new ExceptionResponseBuilder(), new AppProperties());
        String unknownSessionId = "unknown-session-id";

        assertFalse(chatMemoryService.hasSession(unknownSessionId));

        ResponseEntity<SessionValidationResponse> unknownSessionResponse =
                chatController.validateSession(unknownSessionId);
        assertEquals(HttpStatus.OK, unknownSessionResponse.getStatusCode());
        SessionValidationResponse unknownSessionBody = unknownSessionResponse.getBody();
        assertNotNull(unknownSessionBody);
        assertFalse(unknownSessionBody.exists());
        assertEquals("Session not found on server", unknownSessionBody.message());
        assertFalse(chatMemoryService.hasSession(unknownSessionId));

        chatMemoryService.addUser("recognized-session-id", "Stored message");
        ResponseEntity<SessionValidationResponse> recognizedSessionResponse =
                chatController.validateSession("recognized-session-id");
        SessionValidationResponse recognizedSessionBody = recognizedSessionResponse.getBody();
        assertNotNull(recognizedSessionBody);
        assertTrue(recognizedSessionBody.exists());
        assertEquals("Session found", recognizedSessionBody.message());
    }

    @Test
    void validateSession_returnsBadRequestWhenSessionIdIsBlank() {
        ChatMemoryService chatMemoryService = new ChatMemoryService();
        ChatController chatController = new ChatController(
                null, chatMemoryService, null, null, null, new ExceptionResponseBuilder(), new AppProperties());

        ResponseEntity<SessionValidationResponse> response = chatController.validateSession("");
        SessionValidationResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(body.exists());
        assertEquals("Session ID is required", body.message());
    }
}
