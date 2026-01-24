import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Map;

/**
 * Manual probe that prints raw WebFlux SSE chunks from the OpenAI API.
 */
public class TestWebFluxSSE {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    
    /**
     * Runs the raw SSE chunk probe.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }
        
        System.out.println("=== Testing WebFlux SSE Streaming ===");
        
        WebClient webClient = WebClient.builder().build();
        
        Map<String, Object> body = Map.of(
            "model", "gpt-5",
            "messages", java.util.List.of(Map.of("role", "user", "content", "Say 'Hello World' and nothing else")),
            "max_completion_tokens", 100,
            "reasoning_effort", "minimal",
            "stream", true
        );
        
        System.out.println("Sending request...");
        
        Flux<String> stream = webClient.post()
            .uri("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .header("Accept", "text/event-stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class);
        
        System.out.println("\n=== RAW CHUNKS FROM WEBFLUX ===");
        
        stream
            .doOnNext(chunk -> {
                System.out.println("\n--- CHUNK START ---");
                System.out.println("Length: " + chunk.length());
                System.out.println("Content: " + chunk);
                System.out.println("--- CHUNK END ---");
            })
            .doOnComplete(() -> System.out.println("\n=== STREAM COMPLETE ==="))
            .doOnError(error -> {
                System.err.println("Error: " + error.getClass().getSimpleName());
            })
            .blockLast(Duration.ofSeconds(30));
        
        System.out.println("\nTest complete!");
    }
}
