package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

/**
 * Simple OpenAI-compatible EmbeddingModel.
 * Calls {baseUrl}/v1/embeddings with Bearer token and model name.
 * Works with OpenAI and providers like Novita that expose compatible APIs.
 */
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);
    
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;

    private final String baseUrl;           // e.g., https://api.openai.com/openai/v1 or provider base
    private final String apiKey;            // Bearer token
    private final String modelName;         // embedding model id
    private final int dimensionsHint;       // used only as a hint; actual vector size comes from response
	    private final RestTemplate restTemplate;

		private record EmbeddingRequestPayload(String model, List<String> input) {
		}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record EmbeddingApiResponse(@JsonProperty("data") List<EmbeddingApiResponseItem> data) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record EmbeddingApiResponseItem(
            @JsonProperty("index") Integer index,
            @JsonProperty("embedding") List<Double> embedding
        ) {
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
        if (dimensionsHint <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensionsHint = dimensionsHint;
	        this.restTemplate = restTemplateBuilder
	            .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
	            .readTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS))
	            .build();
	    }

        OpenAiCompatibleEmbeddingModel(String baseUrl,
                                      String apiKey,
                                      String modelName,
                                      int dimensionsHint,
                                      RestTemplate restTemplate) {
            if (dimensionsHint <= 0) {
                throw new IllegalArgumentException("Embedding dimensions must be positive");
            }
            this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.apiKey = apiKey;
            this.modelName = modelName;
            this.dimensionsHint = dimensionsHint;
            this.restTemplate = restTemplate;
        }

	    /**
	     * Calls the remote OpenAI-compatible embeddings endpoint for all inputs in the request.
	     */
	    @Override
	    public EmbeddingResponse call(EmbeddingRequest request) {
	        validateConfig();
	        String endpoint = resolveEndpoint(baseUrl);
	        
	        List<String> instructions = request.getInstructions();
            if (instructions == null || instructions.isEmpty()) {
                return new EmbeddingResponse(List.of());
            }
	        EmbeddingRequestPayload payload = new EmbeddingRequestPayload(modelName, instructions);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

	        HttpEntity<EmbeddingRequestPayload> entity = new HttpEntity<>(payload, headers);

	        try {
	            EmbeddingApiResponse response = restTemplate.postForObject(endpoint, entity, EmbeddingApiResponse.class);
	            List<Embedding> embeddings = parseResponse(response, instructions.size());
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

	    private List<Embedding> parseResponse(EmbeddingApiResponse response, int expectedCount) {
	        if (response == null) {
	            throw new EmbeddingApiResponseException("Remote embedding response was null");
	        }
            if (response.data() == null || response.data().isEmpty()) {
                throw new EmbeddingApiResponseException("Remote embedding response missing 'data' entries");
            }

            List<Embedding> embeddingsByIndex = new ArrayList<>(expectedCount);
            for (int index = 0; index < expectedCount; index++) {
                embeddingsByIndex.add(null);
            }

            for (int itemIndex = 0; itemIndex < response.data().size(); itemIndex++) {
                EmbeddingApiResponseItem item = response.data().get(itemIndex);
                int targetIndex = item.index() == null ? itemIndex : item.index();
                if (targetIndex < 0 || targetIndex >= expectedCount) {
                    continue;
                }
                float[] vector = toFloatVector(item.embedding());
                embeddingsByIndex.set(targetIndex, new Embedding(vector, targetIndex));
            }

            List<Embedding> orderedEmbeddings = new ArrayList<>(expectedCount);
            for (int index = 0; index < expectedCount; index++) {
                Embedding embedding = embeddingsByIndex.get(index);
                if (embedding == null) {
                    throw new EmbeddingApiResponseException("Remote embedding response missing embedding for index " + index);
                }
                orderedEmbeddings.add(embedding);
            }

            return orderedEmbeddings;
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

        private float[] toFloatVector(List<Double> embeddingEntries) {
            if (embeddingEntries == null || embeddingEntries.isEmpty()) {
                throw new EmbeddingApiResponseException("Remote embedding response missing embedding values");
            }
            float[] vector = new float[embeddingEntries.size()];
            for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
                Double entry = embeddingEntries.get(vectorIndex);
                if (entry == null) {
                    throw new EmbeddingApiResponseException("Null embedding value at index " + vectorIndex);
                }
                vector[vectorIndex] = entry.floatValue();
            }
            return vector;
        }
}
