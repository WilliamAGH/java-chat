import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Manual probe that prints raw GPT-5 SSE output and extracts content inline.
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
        
        System.out.println("=== Testing GPT-5 SSE Streaming ===");
        System.out.println("API Key present: " + (OPENAI_API_KEY.length() > 0));
        
        String requestBody = """
            {
                "model": "gpt-5",
                "messages": [{"role": "user", "content": "Say 'Hello World' and nothing else"}],
                "max_completion_tokens": 100,
                "reasoning_effort": "minimal",
                "stream": true
            }
            """;
        
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        
        System.out.println("\nSending request to OpenAI...");
        System.out.println("Request body: " + requestBody);
        
        try {
            // Use a regular response to see the raw SSE stream
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("\nResponse Status: " + response.statusCode());
            System.out.println("Response Headers:");
            response.headers().map().forEach((key, headerValues) ->
                System.out.println("  " + key + ": " + headerValues));
            
            System.out.println("\n=== RAW RESPONSE BODY ===");
            String responseBody = response.body();
            System.out.println(responseBody);
            
            System.out.println("\n=== PARSING SSE EVENTS ===");
            String[] lines = responseBody.split("\n");
            int eventCount = 0;
            
            for (String line : lines) {
                if (line.startsWith("data: ")) {
                    eventCount++;
                    String eventPayload = line.substring(6);
                    System.out.println("Event " + eventCount + ": " + eventPayload);
                    
                    String extractedContent = extractInlineContent(eventPayload);
                    if (!extractedContent.isEmpty()) {
                        System.out.println("  -> Extracted content: '" + extractedContent + "'");
                    }
                } else if (line.startsWith(":")) {
                    System.out.println("SSE Comment: " + line);
                } else if (!line.trim().isEmpty()) {
                    System.out.println("Other line: " + line);
                }
            }
            
            System.out.println("\n=== SUMMARY ===");
            System.out.println("Total SSE events: " + eventCount);
            System.out.println("Response complete!");
            
        } catch (IOException | InterruptedException exception) {
            System.err.println("Error: " + exception.getClass().getSimpleName());
        }
    }

    private static String extractInlineContent(String eventPayload) {
        if ("[DONE]".equals(eventPayload) || eventPayload.isEmpty()) {
            return "";
        }
        if (!eventPayload.contains("\"delta\"") || !eventPayload.contains("\"content\"")) {
            return "";
        }
        int contentStart = eventPayload.indexOf("\"content\":\"") + 11;
        if (contentStart <= 10 || contentStart >= eventPayload.length()) {
            return "";
        }
        int contentEnd = eventPayload.indexOf("\"", contentStart);
        if (contentEnd <= contentStart) {
            return "";
        }
        return eventPayload.substring(contentStart, contentEnd);
    }
}
