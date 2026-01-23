import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.Map;
import java.util.List;

public class TestWhatGetsStreamed {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public static void main(String[] args) throws Exception {
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
                if (chunk == null || chunk.trim().isEmpty() || chunk.equals("[DONE]")) {
                    return Flux.empty();
                }
                
                try {
                    Map<String, Object> data = objectMapper.readValue(chunk, new TypeReference<Map<String, Object>>() {});
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
                                        System.out.println("Extracted: '" + text + "'");
                                        return Flux.just(text);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Parse error: " + e.getMessage());
                }
                return Flux.empty();
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
}
