import com.fasterxml.jackson.databind.ObjectMapper;
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

        System.out.println("=== SIMULATING ChatController BEHAVIOR ===\n");

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(OPENAI_API_KEY)
                .maxRetries(0)
                .build();

        Timeout timeout = Timeout.builder()
                .request(Duration.ofSeconds(30))
                .read(Duration.ofSeconds(30))
                .build();

        RequestOptions requestOptions =
                RequestOptions.builder().timeout(timeout).build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of("gpt-5.2"))
                .maxCompletionTokens(50)
                .reasoningEffort(ReasoningEffort.of("minimal"))
                .addUserMessage("Say hello")
                .build();

        try (StreamResponse<ChatCompletionChunk> responseStream =
                client.chat().completions().createStreaming(params, requestOptions)) {
            responseStream.stream()
                    .flatMap(chunk -> chunk.choices().stream())
                    .flatMap(choice -> choice.delta().content().stream())
                    .filter(contentChunk -> !contentChunk.isEmpty())
                    .forEach(contentChunk -> {
                        System.out.println("Extracted: '" + contentChunk + "'");
                        String jsonPayload = jsonTextPayload(contentChunk);
                        String sseFrame = "data: " + jsonPayload + "\n\n";
                        System.out.println("Sending to browser: '" + sseFrame.replace("\n", "\\n") + "'");
                    });
        } catch (RuntimeException streamingFailure) {
            System.err.println(
                    "\nError during streaming: " + streamingFailure.getClass().getSimpleName());
        } finally {
            client.close();
        }
    }

    private static String jsonTextPayload(String chunk) {
        try {
            return objectMapper.writeValueAsString(new TextEvent(chunk));
        } catch (Exception jsonFailure) {
            System.err.println(
                    "JSON serialization error: " + jsonFailure.getClass().getSimpleName());
            return "{\"text\":\"\"}";
        }
    }

    private record TextEvent(String text) {}
}
