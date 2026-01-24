import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.time.Duration;

/**
 * Manual probe that prints raw streaming chunks from the OpenAI Java SDK.
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
        
        System.out.println("=== Testing OpenAI Java SDK Streaming ===");
        System.out.println("Sending request...");

        OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey(OPENAI_API_KEY)
            .maxRetries(0)
            .build();

        Timeout timeout = Timeout.builder()
            .request(Duration.ofSeconds(30))
            .read(Duration.ofSeconds(30))
            .build();

        RequestOptions requestOptions = RequestOptions.builder()
            .timeout(timeout)
            .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of("gpt-5.2"))
            .maxCompletionTokens(100)
            .reasoningEffort(ReasoningEffort.of("minimal"))
            .addUserMessage("Say 'Hello World' and nothing else")
            .build();
        
        System.out.println("\n=== RAW CHUNKS FROM OPENAI-JAVA ===");

        try (StreamResponse<ChatCompletionChunk> responseStream =
                 client.chat().completions().createStreaming(params, requestOptions)) {
            responseStream.stream().forEach(chunk -> {
                System.out.println("\n--- CHUNK START ---");
                System.out.println("Chunk: " + chunk);
                chunk.choices().stream()
                    .flatMap(choice -> choice.delta().content().stream())
                    .forEach(contentChunk -> System.out.println("Delta: " + contentChunk));
                System.out.println("--- CHUNK END ---");
            });
            System.out.println("\n=== STREAM COMPLETE ===");
        } catch (RuntimeException streamingFailure) {
            System.err.println("Error: " + streamingFailure.getClass().getSimpleName());
        } finally {
            client.close();
        }
        
        System.out.println("\nTest complete!");
    }
}
