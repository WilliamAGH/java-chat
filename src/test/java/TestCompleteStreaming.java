import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/**
 * Manual smoke test for end-to-end GPT-5 streaming content extraction.
 */
public class TestCompleteStreaming {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Runs the streaming extraction test against the OpenAI API.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }
        
        System.out.println("=== Testing Complete GPT-5 Streaming Pipeline ===\n");
        
        WebClient webClient = WebClient.builder().build();
        
        Map<String, Object> body = Map.of(
            "model", "gpt-5",
            "messages", List.of(Map.of("role", "user", "content", "What is Spring Boot? Give a very brief answer.")),
            "max_completion_tokens", 200,
            "reasoning_effort", "minimal",
            "stream", true
        );
        
        System.out.println("Sending request to GPT-5...\n");
        
        Flux<String> stream = webClient.post()
            .uri("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .header("Accept", "text/event-stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class);
        
        StringBuilder fullResponse = new StringBuilder();
        System.out.println("=== STREAMING RESPONSE ===");
        
        stream
            .flatMap(chunk -> {
                String extractedContent = extractContent(chunk);
                return extractedContent.isEmpty() ? Flux.empty() : Flux.just(extractedContent);
            })
            .doOnNext(content -> {
                // Print each content chunk as it arrives
                System.out.print(content);
                fullResponse.append(content);
            })
            .doOnComplete(() -> {
                System.out.println("\n\n=== STREAM COMPLETE ===");
                System.out.println("Full response length: " + fullResponse.length() + " characters");
                if (fullResponse.length() == 0) {
                    System.err.println("ERROR: No content was extracted from the stream!");
                } else {
                    System.out.println("SUCCESS: Content was properly extracted and displayed!");
                }
            })
            .doOnError(error -> {
                System.err.println("\nError during streaming: " + error.getClass().getSimpleName());
            })
            .blockLast(Duration.ofSeconds(60));
        
        System.out.println("\nTest complete!");
    }

    private static String extractContent(String chunk) {
        if (chunk == null) {
            return "";
        }
        String trimmedChunk = chunk.trim();
        if (trimmedChunk.isEmpty() || "[DONE]".equals(trimmedChunk)) {
            return "";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(trimmedChunk, new TypeReference<Map<String, Object>>() {});
            return extractDeltaContent(payload).orElse("");
        } catch (IOException parseFailure) {
            System.err.println("Parse error for chunk: " + parseFailure.getMessage());
            return "";
        }
    }

    private static Optional<String> extractDeltaContent(Map<String, Object> payload) {
        Object choicesRaw = payload.get("choices");
        if (!(choicesRaw instanceof List<?> choicesList) || choicesList.isEmpty()) {
            return Optional.empty();
        }
        Object choiceRaw = choicesList.get(0);
        if (!(choiceRaw instanceof Map<?, ?> choiceMap)) {
            return Optional.empty();
        }
        Object deltaRaw = choiceMap.get("delta");
        if (!(deltaRaw instanceof Map<?, ?> deltaMap)) {
            return Optional.empty();
        }
        Object contentRaw = deltaMap.get("content");
        if (contentRaw == null) {
            return Optional.empty();
        }
        String contentText = contentRaw.toString();
        return contentText.isEmpty() ? Optional.empty() : Optional.of(contentText);
    }
}
