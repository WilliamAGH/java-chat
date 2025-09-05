import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.Map;
import java.util.List;

public class TestCompleteStreaming {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public static void main(String[] args) throws Exception {
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
                // Exact same logic as in the fixed ResilientApiClient
                if (chunk == null || chunk.trim().isEmpty() || chunk.equals("[DONE]")) {
                    return Flux.empty();
                }
                
                try {
                    // Parse the raw JSON chunk directly
                    Map<String, Object> data = objectMapper.readValue(chunk, new TypeReference<Map<String, Object>>() {});
                    
                    // Extract content from the delta field
                    Object choicesObj = data.get("choices");
                    if (choicesObj instanceof List) {
                        List<?> choices = (List<?>) choicesObj;
                        if (!choices.isEmpty()) {
                            Object firstChoiceObj = choices.get(0);
                            if (firstChoiceObj instanceof Map) {
                                Map<?, ?> firstChoice = (Map<?, ?>) firstChoiceObj;
                                Object deltaObj = firstChoice.get("delta");
                                if (deltaObj instanceof Map) {
                                    Map<?, ?> delta = (Map<?, ?>) deltaObj;
                                    Object content = delta.get("content");
                                    if (content != null && !content.toString().isEmpty()) {
                                        String text = content.toString();
                                        return Flux.just(text);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse chunk: " + e.getMessage());
                }
                return Flux.empty();
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
                System.err.println("\nError: " + error.getMessage());
                error.printStackTrace();
            })
            .blockLast(Duration.ofSeconds(60));
        
        System.out.println("\nTest complete!");
        
        // Exit with proper code
        System.exit(fullResponse.length() > 0 ? 0 : 1);
    }
}
