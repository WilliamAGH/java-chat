import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class TestGPT5Streaming {
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    
    public static void main(String[] args) throws Exception {
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
            response.headers().map().forEach((key, value) -> 
                System.out.println("  " + key + ": " + value));
            
            System.out.println("\n=== RAW RESPONSE BODY ===");
            String body = response.body();
            System.out.println(body);
            
            System.out.println("\n=== PARSING SSE EVENTS ===");
            String[] lines = body.split("\n");
            int eventCount = 0;
            
            for (String line : lines) {
                if (line.startsWith("data: ")) {
                    eventCount++;
                    String data = line.substring(6);
                    System.out.println("Event " + eventCount + ": " + data);
                    
                    if (!data.equals("[DONE]") && !data.isEmpty()) {
                        try {
                            // Parse the JSON to extract content
                            if (data.contains("\"delta\"") && data.contains("\"content\"")) {
                                int contentStart = data.indexOf("\"content\":\"") + 11;
                                int contentEnd = data.indexOf("\"", contentStart);
                                if (contentStart > 10 && contentEnd > contentStart) {
                                    String content = data.substring(contentStart, contentEnd);
                                    System.out.println("  -> Extracted content: '" + content + "'");
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("  -> Failed to extract content: " + e.getMessage());
                        }
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
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
