package com.williamcallahan.javachat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple OpenAI-compatible EmbeddingModel.
 * Calls {baseUrl}/v1/embeddings with Bearer token and model name.
 * Works with OpenAI and providers like Novita that expose compatible APIs.
 */
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);
    
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 4096;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;

    private final String baseUrl;           // e.g., https://api.openai.com/openai/v1 or provider base
    private final String apiKey;            // Bearer token
    private final String modelName;         // embedding model id
    private final int dimensionsHint;       // used only as a hint; actual vector size comes from response
    private final RestTemplate restTemplate;

	private record EmbeddingRequestPayload(String model, List<String> input) {
	}

	/**
	 * Wraps remote embedding API failures as a runtime exception with concise context.
	 */
	private static final class EmbeddingApiResponseException extends IllegalStateException {
		private EmbeddingApiResponseException(String message, Exception cause) {
			super(message, cause);
		}

        private EmbeddingApiResponseException(String message) {
			super(message);
		}
	}

	/**
	 * Creates an OpenAI-compatible embedding model backed by a remote REST API endpoint.
	 *
	 * @param baseUrl base URL for the embedding API
	 * @param apiKey API key for the embedding provider
	 * @param modelName model identifier for embeddings
	 * @param dimensionsHint expected embedding dimensions (used as a hint)
	 * @param restTemplateBuilder RestTemplate builder for HTTP calls
	 */
	public OpenAiCompatibleEmbeddingModel(String baseUrl,
	                                      String apiKey,
	                                      String modelName,
	                                      int dimensionsHint,
	                                      RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensionsHint = dimensionsHint > 0 ? dimensionsHint : DEFAULT_EMBEDDING_DIMENSIONS;
        this.restTemplate = restTemplateBuilder
            .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .readTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS))
            .build();
    }

    /**
     * Calls the remote OpenAI-compatible embeddings endpoint for all inputs in the request.
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        validateConfig();
        String endpoint = resolveEndpoint(baseUrl);
        
        List<String> instructions = request.getInstructions();
        EmbeddingRequestPayload payload = new EmbeddingRequestPayload(modelName, instructions);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<EmbeddingRequestPayload> entity = new HttpEntity<>(payload, headers);

        try {
            Object rawResponse = restTemplate.postForObject(endpoint, entity, Object.class);
            List<Embedding> embeddings = parseResponse(rawResponse);
            return new EmbeddingResponse(embeddings);

        } catch (EmbeddingApiResponseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("[EMBEDDING] Remote embedding call failed (exception type: {})",
                exception.getClass().getSimpleName());
            throw new EmbeddingApiResponseException("Remote embedding call failed", exception);
        }
    }

    private void validateConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Remote embedding API key is not configured");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Remote embedding base URL is not configured");
        }
    }

    private String resolveEndpoint(String baseUrl) {
        String endpoint = baseUrl;
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.endsWith("/v1/embeddings")) {
            if (endpoint.endsWith("/v1")) {
                endpoint = endpoint + "/embeddings";
            } else if (!endpoint.contains("/v1/embeddings")) {
                endpoint = endpoint + "/v1/embeddings";
            }
        }
        return endpoint;
    }

    private List<Embedding> parseResponse(Object rawResponse) {
        if (rawResponse == null) {
            throw new EmbeddingApiResponseException("Remote embedding response was null");
        }

        Map<?, ?> responseMap = requireMap(rawResponse, "response");
        Object responsePayload = responseMap.get("data");
        if (!(responsePayload instanceof List<?> responseEntries) || responseEntries.isEmpty()) {
            throw new EmbeddingApiResponseException("Remote embedding response missing 'data' entries");
        }

        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < responseEntries.size(); i++) {
            Object entryObj = responseEntries.get(i);
            Map<?, ?> entryMap = requireMap(entryObj, "data[" + i + "]");
            Object embeddingPayload = entryMap.get("embedding");

            if (!(embeddingPayload instanceof List<?> embeddingEntries) || embeddingEntries.isEmpty()) {
                throw new EmbeddingApiResponseException("Remote embedding response missing embedding values");
            }

            float[] vector = new float[embeddingEntries.size()];
            for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
                Object numericEntry = embeddingEntries.get(vectorIndex);
                if (!(numericEntry instanceof Number numberValue)) {
                    throw new EmbeddingApiResponseException("Non-numeric embedding value at index " + vectorIndex);
                }
                vector[vectorIndex] = numberValue.floatValue();
            }
            embeddings.add(new Embedding(vector, i));
        }
        return embeddings;
    }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Remote embedding base URL is not configured");
        }

        // Build endpoint robustly. Support users passing either a base (e.g., https://api.openai.com)
        // or a full path including /v1/embeddings. Avoid double-appending.
        String endpoint = baseUrl;
        // Strip trailing slash for normalization
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.endsWith("/v1/embeddings")) {
            if (endpoint.endsWith("/v1")) {
                endpoint = endpoint + "/embeddings";
            } else if (!endpoint.contains("/v1/embeddings")) {
                endpoint = endpoint + "/v1/embeddings";
            }
        }
        
        List<String> instructions = request.getInstructions();
        EmbeddingRequestPayload payload = new EmbeddingRequestPayload(modelName, instructions);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<EmbeddingRequestPayload> entity = new HttpEntity<>(payload, headers);

        try {
            Object rawResponse = restTemplate.postForObject(endpoint, entity, Object.class);
            if (rawResponse == null) {
                throw new EmbeddingApiResponseException("Remote embedding response was null");
            }

            Map<?, ?> responseMap = requireMap(rawResponse, "response");
            Object responsePayload = responseMap.get("data");
            if (!(responsePayload instanceof List<?> responseEntries) || responseEntries.isEmpty()) {
                throw new EmbeddingApiResponseException("Remote embedding response missing 'data' entries");
            }

            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < responseEntries.size(); i++) {
                Object entryObj = responseEntries.get(i);
                Map<?, ?> entryMap = requireMap(entryObj, "data[" + i + "]");
                Object embeddingPayload = entryMap.get("embedding");

                if (!(embeddingPayload instanceof List<?> embeddingEntries) || embeddingEntries.isEmpty()) {
                    throw new EmbeddingApiResponseException("Remote embedding response missing embedding values");
                }

                float[] vector = new float[embeddingEntries.size()];
                for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
                    Object numericEntry = embeddingEntries.get(vectorIndex);
                    if (!(numericEntry instanceof Number numberValue)) {
                        throw new EmbeddingApiResponseException("Non-numeric embedding value at index " + vectorIndex);
                    }
                    vector[vectorIndex] = numberValue.floatValue();
                }
                embeddings.add(new Embedding(vector, i));
            }
            
            return new EmbeddingResponse(embeddings);

        } catch (EmbeddingApiResponseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("[EMBEDDING] Remote embedding call failed (exception type: {})",
                exception.getClass().getSimpleName());
            throw new EmbeddingApiResponseException("Remote embedding call failed", exception);
        }
    }

    /**
     * Returns the configured dimension hint for downstream vector store setup.
     */
    @Override
    public int dimensions() {
        return dimensionsHint;
    }

    /**
     * Embeds a single document by delegating to the remote embeddings endpoint.
     */
    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        EmbeddingRequest embeddingRequest = new EmbeddingRequest(List.of(document.getText()), null);
        EmbeddingResponse embeddingResponse = call(embeddingRequest);
        if (embeddingResponse.getResults().isEmpty()) {
            throw new EmbeddingApiResponseException("Embedding response was empty");
        }
        return embeddingResponse.getResults().get(0).getOutput();
    }

    private Map<?, ?> requireMap(Object candidate, String description) {
        if (candidate == null) {
            throw new EmbeddingApiResponseException("Expected map for " + description + ", got null");
        }
        if (candidate instanceof Map<?, ?> mappedResponse) {
            return mappedResponse;
        }
        throw new EmbeddingApiResponseException("Expected map for " + description + ", got " + candidate.getClass().getName());
    }
}
