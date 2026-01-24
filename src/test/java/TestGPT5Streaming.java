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
 * Manual probe that prints GPT-5 streaming output using the OpenAI Java SDK.
 */
public class TestGPT5Streaming {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    
    /**
     * Runs the SSE probe against the OpenAI API.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isEmpty()) {
            System.err.println("Please set OPENAI_API_KEY environment variable");
            System.exit(1);
        }
        
        System.out.println("=== Testing GPT-5 Streaming ===");
        System.out.println("API Key present: " + (OPENAI_API_KEY.length() > 0));

        System.out.println("\nSending request to OpenAI...\n");

        OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey(OPENAI_API_KEY)
            .maxRetries(0)
            .build();

        Timeout timeout = Timeout.builder()
            .request(Duration.ofSeconds(60))
            .read(Duration.ofSeconds(60))
            .build();

        RequestOptions requestOptions = RequestOptions.builder()
            .timeout(timeout)
            .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of("gpt-5"))
            .maxCompletionTokens(100)
            .reasoningEffort(ReasoningEffort.of("minimal"))
            .addUserMessage("Say 'Hello World' and nothing else")
            .build();

        StringBuilder fullResponse = new StringBuilder();
        java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);

        try (StreamResponse<ChatCompletionChunk> responseStream =
                 client.chat().completions().createStreaming(params, requestOptions)) {
            responseStream.stream().forEach(chunk -> {
                chunkCount.incrementAndGet();
                chunk.choices().stream()
                    .flatMap(choice -> choice.delta().content().stream())
                    .forEach(contentChunk -> {
                        System.out.print(contentChunk);
                        fullResponse.append(contentChunk);
                    });
            });
        } catch (RuntimeException streamingFailure) {
            System.err.println("Error: " + streamingFailure.getClass().getSimpleName());
        } finally {
            client.close();
        }

        System.out.println("\n\n=== SUMMARY ===");
        System.out.println("Total chunks: " + chunkCount.get());
        System.out.println("Full response length: " + fullResponse.length() + " characters");
        System.out.println("Response complete!");
    }
}
