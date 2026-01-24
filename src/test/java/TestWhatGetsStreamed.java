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
 * Manual probe that mirrors ChatController chunk handling for SSE wrapping.
 */
public class TestWhatGetsStreamed {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Runs the streaming probe against the OpenAI API.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }
        
        System.out.println("=== Testing What Gets Sent to Browser ===\n");
        
        WebClient webClient = WebClient.builder().build();
        
        Map<String, Object> body = Map.of(
            "model", "gpt-5",
            "messages", List.of(Map.of("role", "user", "content", "Say hello")),
            "max_completion_tokens", 50,
            "reasoning_effort", "minimal",
            "stream", true
        );
        
        Flux<String> stream = webClient.post()
            .uri("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class);
        
        System.out.println("=== SIMULATING ChatController BEHAVIOR ===\n");
        
        // This simulates what ChatController does
        stream
            .flatMap(chunk -> {
                String extractedContent = extractContent(chunk);
                if (extractedContent.isEmpty()) {
                    return Flux.empty();
                }
                System.out.println("Extracted: '" + extractedContent + "'");
                return Flux.just(extractedContent);
            })
            .map(content -> {
                // This is what ChatController does - wraps in SSE format
                String sseEvent = "data: " + content + "\n\n";
                System.out.println("Sending to browser: '" + sseEvent.replace("\n", "\\n") + "'");
                return sseEvent;
            })
            .blockLast(Duration.ofSeconds(30));
        
        System.out.println("\n=== PROBLEM IDENTIFIED ===");
        System.out.println("The issue is that ChatController wraps the content with 'data: '");
        System.out.println("But the content ITSELF sometimes contains 'data:' text!");
        System.out.println("This creates 'data: ...data:...' which confuses the browser!");
    }

    /**
     * Extracts content from a streaming chunk, returning empty string for unparseable chunks.
     *
     * <p>This manual test utility intentionally continues on parse failures to observe the
     * full stream behavior. Parse errors are logged to stderr for visibility during manual
     * debugging sessions. This is not production code—real streaming handlers should propagate
     * parse failures or use typed result containers.
     */
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
            // Intentional: log and continue to observe full stream in manual testing
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
