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
 * Manual smoke test for end-to-end GPT-5.2 streaming content extraction.
 */
public class TestCompleteStreaming {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    
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
        
        System.out.println("=== Testing Complete GPT-5.2 Streaming Pipeline ===\n");
        
        StringBuilder fullResponse = new StringBuilder();
        System.out.println("Sending request to GPT-5.2...\n");
        System.out.println("=== STREAMING RESPONSE ===");

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
            .model(ChatModel.of("gpt-5.2"))
            .maxCompletionTokens(200)
            .reasoningEffort(ReasoningEffort.of("minimal"))
            .addUserMessage("What is Spring Boot? Give a very brief answer.")
            .build();

        try (StreamResponse<ChatCompletionChunk> responseStream =
                 client.chat().completions().createStreaming(params, requestOptions)) {
            responseStream.stream()
                .flatMap(chunk -> chunk.choices().stream())
                .flatMap(choice -> choice.delta().content().stream())
                .forEach(contentChunk -> {
                    System.out.print(contentChunk);
                    fullResponse.append(contentChunk);
                });
        } catch (RuntimeException streamingFailure) {
            System.err.println("\nError during streaming: " + streamingFailure.getClass().getSimpleName());
        } finally {
            client.close();
        }

        System.out.println("\n\n=== STREAM COMPLETE ===");
        System.out.println("Full response length: " + fullResponse.length() + " characters");
        if (fullResponse.length() == 0) {
            System.err.println("ERROR: No content was extracted from the stream!");
        } else {
            System.out.println("SUCCESS: Content was properly extracted and displayed!");
        }
        
        System.out.println("\nTest complete!");
    }
}
