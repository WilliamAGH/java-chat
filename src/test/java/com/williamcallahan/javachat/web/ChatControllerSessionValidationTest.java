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

    private static final String UNKNOWN_SESSION_ID = "unknown-session-id";
    private static final String RECOGNIZED_SESSION_ID = "recognized-session-id";
    private static final String STORED_MESSAGE_TEXT = "Stored message";
    private static final String SESSION_NOT_FOUND_MESSAGE = "Session not found on server";
    private static final String SESSION_FOUND_MESSAGE = "Session found";
    private static final String SESSION_ID_REQUIRED_MESSAGE = "Session ID is required";

    @Test
    void validateSession_doesNotCreateUnknownSessionAndReportsRecognizedSessionHistory() {
        ChatMemoryService chatMemoryService = new ChatMemoryService();
        ChatController chatController = new ChatController(
                null, chatMemoryService, null, null, null, new ExceptionResponseBuilder(), new AppProperties());

        assertFalse(chatMemoryService.hasSession(UNKNOWN_SESSION_ID));

        ResponseEntity<SessionValidationResponse> unknownSessionEntity =
                chatController.validateSession(UNKNOWN_SESSION_ID);
        assertEquals(HttpStatus.OK, unknownSessionEntity.getStatusCode());
        SessionValidationResponse unknownSessionBody = unknownSessionEntity.getBody();
        assertNotNull(unknownSessionBody);
        assertFalse(unknownSessionBody.exists());
        assertEquals(SESSION_NOT_FOUND_MESSAGE, unknownSessionBody.message());
        assertFalse(chatMemoryService.hasSession(UNKNOWN_SESSION_ID));

        chatMemoryService.addUser(RECOGNIZED_SESSION_ID, STORED_MESSAGE_TEXT);
        ResponseEntity<SessionValidationResponse> recognizedSessionEntity =
                chatController.validateSession(RECOGNIZED_SESSION_ID);
        SessionValidationResponse recognizedSessionBody = recognizedSessionEntity.getBody();
        assertNotNull(recognizedSessionBody);
        assertTrue(recognizedSessionBody.exists());
        assertEquals(SESSION_FOUND_MESSAGE, recognizedSessionBody.message());
    }

    @Test
    void validateSession_returnsBadRequestWhenSessionIdIsBlank() {
        ChatMemoryService chatMemoryService = new ChatMemoryService();
        ChatController chatController = new ChatController(
                null, chatMemoryService, null, null, null, new ExceptionResponseBuilder(), new AppProperties());

        ResponseEntity<SessionValidationResponse> blankSessionEntity = chatController.validateSession("");
        SessionValidationResponse blankSessionBody = blankSessionEntity.getBody();
        assertNotNull(blankSessionBody);
        assertEquals(HttpStatus.BAD_REQUEST, blankSessionEntity.getStatusCode());
        assertFalse(blankSessionBody.exists());
        assertEquals(SESSION_ID_REQUIRED_MESSAGE, blankSessionBody.message());
    }
}
