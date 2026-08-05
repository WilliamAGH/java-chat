package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.application.contact.ContactSubmissionUseCase;
import com.williamcallahan.javachat.config.AppProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifies the contact-form contract end to end through the web layer with a mocked mail sender:
 * delivery of valid submissions, silent spam drops, rate limiting, and validation failures.
 */
@WebMvcTest(controllers = ContactController.class)
@Import({AppProperties.class, ExceptionResponseBuilder.class, ContactSubmissionUseCase.class})
@TestPropertySource(
        properties = {
            "app.contact.recipient-email=support@example.test",
            "app.contact.sender-email=noreply@example.test"
        })
@WithMockUser
class ContactControllerTest {
    private static final String CONTACT_ENDPOINT = "/api/contact";
    private static final long LEGITIMATE_RENDER_AGE_MILLIS = 10_000L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JavaMailSender javaMailSender;

    @BeforeEach
    void stubMimeMessageCreation() {
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    @Test
    void validSubmissionSendsMailAndReturnsAccepted() throws Exception {
        String senderIp = "198.51.100.1";

        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress(senderIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(
                                "Ada Lovelace",
                                "ada@example.test",
                                "The guided lessons helped me finally understand streams.",
                                "",
                                legitimateRenderedAt())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        ArgumentCaptor<MimeMessage> sentMessageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender, times(1)).send(sentMessageCaptor.capture());
        MimeMessage deliveredMessage = sentMessageCaptor.getValue();
        assertEquals("Java Chat contact: Ada Lovelace", deliveredMessage.getSubject());
        assertEquals("ada@example.test", deliveredMessage.getReplyTo()[0].toString());
    }

    @Test
    void honeypotContentIsDroppedSilentlyWithoutSendingMail() throws Exception {
        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress("198.51.100.2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(
                                "Spam Bot",
                                "bot@example.test",
                                "Buy my SEO services",
                                "http://spam.example",
                                legitimateRenderedAt())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void submissionYoungerThanRenderTrapIsDroppedSilentlyWithoutSendingMail() throws Exception {
        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress("198.51.100.3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(
                                "Fast Bot",
                                "fast@example.test",
                                "Submitted in milliseconds",
                                "",
                                System.currentTimeMillis())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void missingRenderTimestampIsDroppedSilentlyWithoutSendingMail() throws Exception {
        String requestJson = """
                {"name": "No Timestamp", "email": "gap@example.test", "message": "No renderedAt field", "website": ""}
                """;

        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress("198.51.100.7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void nonPositiveRenderTimestampIsDroppedSilentlyWithoutSendingMail(long renderedAtEpochMillis) throws Exception {
        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress("198.51.100.8"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(
                                "Invalid Timestamp",
                                "invalid-timestamp@example.test",
                                "This request must not reach email delivery.",
                                "",
                                renderedAtEpochMillis)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void fourthAcceptedSubmissionFromSameIpIsRateLimited() throws Exception {
        String senderIp = "198.51.100.4";
        for (int submissionIndex = 0; submissionIndex < 3; submissionIndex++) {
            mockMvc.perform(post(CONTACT_ENDPOINT)
                            .with(csrf())
                            .with(remoteAddress(senderIp))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submissionJson(
                                    "Repeat Sender",
                                    "repeat@example.test",
                                    "Follow-up number " + submissionIndex,
                                    "",
                                    legitimateRenderedAt())))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress(senderIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(
                                "Repeat Sender",
                                "repeat@example.test",
                                "One message too many",
                                "",
                                legitimateRenderedAt())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("error"));

        verify(javaMailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    void lineBreaksInNameAreRejectedAsHeaderInjection() throws Exception {
        String requestJson =
                "{\"name\": \"Evil\\r\\nBcc: victim@example.test\", \"email\": \"sender@example.test\", \"message\": \"Header injection attempt\", \"website\": \"\", \"renderedAt\": %d}"
                        .formatted(legitimateRenderedAt());

        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress("198.51.100.5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void malformedEmailIsRejected() throws Exception {
        mockMvc.perform(post(CONTACT_ENDPOINT)
                        .with(csrf())
                        .with(remoteAddress("198.51.100.6"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionJson(
                                "Bad Address", "not-an-email", "My email field is broken", "", legitimateRenderedAt())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    private static long legitimateRenderedAt() {
        return System.currentTimeMillis() - LEGITIMATE_RENDER_AGE_MILLIS;
    }

    private static RequestPostProcessor remoteAddress(String senderIp) {
        return request -> {
            request.setRemoteAddr(senderIp);
            return request;
        };
    }

    private static String submissionJson(String name, String email, String message, String website, long renderedAt) {
        return "{\"name\": \"%s\", \"email\": \"%s\", \"message\": \"%s\", \"website\": \"%s\", \"renderedAt\": %d}"
                .formatted(name, email, message, website, renderedAt);
    }
}
