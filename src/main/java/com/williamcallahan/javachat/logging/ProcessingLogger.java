package com.williamcallahan.javachat.logging;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.AfterReturning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;


import java.util.Map;
import java.util.HashMap;

/**
 * Comprehensive logging aspect for all processing steps in the RAG pipeline.
 * Logs each step with detailed information for debugging and monitoring.
 */
@Aspect
@Component
public class ProcessingLogger {
    @SuppressWarnings("unused") // Used by logging framework
    private static final Logger log = LoggerFactory.getLogger(ProcessingLogger.class);
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Thread-local storage for request tracking
    private static final ThreadLocal<String> REQUEST_ID = ThreadLocal.withInitial(() -> 
        "REQ-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId()
    );
    
    /**
     * Log embedding generation
     */
    @Around("@annotation(org.springframework.web.bind.annotation.PostMapping) && " +
            "execution(* com.williamcallahan.javachat.service.*EmbeddingModel.embed(..))")
    public Object logEmbeddingGeneration(ProceedingJoinPoint joinPoint) throws Throwable {
        String requestId = REQUEST_ID.get();
        long startTime = System.currentTimeMillis();
        
        PIPELINE_LOG.info("[{}] STEP 1: EMBEDDING GENERATION - Starting", requestId);
        PIPELINE_LOG.debug("[{}] Input text length: {}", requestId, 
            joinPoint.getArgs()[0].toString().length());
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            PIPELINE_LOG.info("[{}] STEP 1: EMBEDDING GENERATION - Completed in {}ms", 
                requestId, duration);
            
            return result;
        } catch (Exception e) {
            PIPELINE_LOG.error("[{}] STEP 1: EMBEDDING GENERATION - Failed: {}", 
                requestId, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Log document parsing
     */
    @Around("execution(* com.williamcallahan.javachat.service.ChunkProcessingService.process*(..))")
    public Object logDocumentParsing(ProceedingJoinPoint joinPoint) throws Throwable {
        String requestId = REQUEST_ID.get();
        long startTime = System.currentTimeMillis();
        
        PIPELINE_LOG.info("[{}] STEP 2: DOCUMENT PARSING - Starting", requestId);
        PIPELINE_LOG.debug("[{}] Processing method: {}", requestId, joinPoint.getSignature().getName());
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            PIPELINE_LOG.info("[{}] STEP 2: DOCUMENT PARSING - Completed in {}ms", 
                requestId, duration);
            
            return result;
        } catch (Exception e) {
            PIPELINE_LOG.error("[{}] STEP 2: DOCUMENT PARSING - Failed: {}", 
                requestId, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Log query intent interpretation
     */
    @Before("execution(* com.williamcallahan.javachat.service.ChatService.*(..)) && " +
            "args(query,..)")
    public void logQueryIntentInterpretation(JoinPoint joinPoint, Object query) {
        String requestId = REQUEST_ID.get();
        
        PIPELINE_LOG.info("[{}] STEP 3: QUERY INTENT INTERPRETATION - Starting", requestId);
        PIPELINE_LOG.info("[{}] User query: {}", requestId, query);
        
        // Analyze query intent
        String intent = analyzeIntent(query.toString());
        PIPELINE_LOG.info("[{}] Detected intent: {}", requestId, intent);
    }
    
    /**
     * Log RAG retrieval
     */
    @Around("execution(* com.williamcallahan.javachat.service.RetrievalService.retrieve*(..))")
    public Object logRAGRetrieval(ProceedingJoinPoint joinPoint) throws Throwable {
        String requestId = REQUEST_ID.get();
        long startTime = System.currentTimeMillis();
        
        PIPELINE_LOG.info("[{}] STEP 4: RAG RETRIEVAL - Starting vector search", requestId);
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            PIPELINE_LOG.info("[{}] STEP 4: RAG RETRIEVAL - Retrieved documents in {}ms", 
                requestId, duration);
            
            // Log retrieved document count if available
            if (result instanceof java.util.List) {
                PIPELINE_LOG.info("[{}] Retrieved {} documents", 
                    requestId, ((java.util.List<?>) result).size());
            }
            
            return result;
        } catch (Exception e) {
            PIPELINE_LOG.error("[{}] STEP 4: RAG RETRIEVAL - Failed: {}", 
                requestId, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Log LLM interaction
     */
    @Around("execution(* com.williamcallahan.javachat.service.ChatService.streamAnswer(..))")
    public Object logLLMInteraction(ProceedingJoinPoint joinPoint) throws Throwable {
        String requestId = REQUEST_ID.get();
        long startTime = System.currentTimeMillis();
        
        PIPELINE_LOG.info("[{}] STEP 5: LLM INTERACTION - Sending to LLM", requestId);
        
        // Log the prompt being sent
        Object[] args = joinPoint.getArgs();
        if (args.length > 0) {
            PIPELINE_LOG.debug("[{}] Prompt context size: {}", requestId, 
                args[0].toString().length());
        }
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            PIPELINE_LOG.info("[{}] STEP 5: LLM INTERACTION - Response streaming started in {}ms", 
                requestId, duration);
            
            return result;
        } catch (Exception e) {
            PIPELINE_LOG.error("[{}] STEP 5: LLM INTERACTION - Failed: {}", 
                requestId, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Log response categorization
     */
    @AfterReturning(
        pointcut = "execution(* com.williamcallahan.javachat.service.EnrichmentService.enrich*(..))",
        returning = "result"
    )
    public void logResponseCategorization(JoinPoint joinPoint, Object result) {
        String requestId = REQUEST_ID.get();
        
        PIPELINE_LOG.info("[{}] STEP 6: RESPONSE CATEGORIZATION - Processing enrichments", requestId);
        
        try {
            if (result instanceof com.williamcallahan.javachat.model.Enrichment) {
                com.williamcallahan.javachat.model.Enrichment enrichment = 
                    (com.williamcallahan.javachat.model.Enrichment) result;
                
                // Log enrichment details
                PIPELINE_LOG.info("[{}] Enrichment categories extracted:", requestId);
                if (enrichment.getHints() != null && !enrichment.getHints().isEmpty()) {
                    PIPELINE_LOG.info("[{}]   - HINTS: {} items", requestId, enrichment.getHints().size());
                    for (String hint : enrichment.getHints()) {
                        PIPELINE_LOG.debug("[{}]     • {}", requestId, hint);
                    }
                }
                if (enrichment.getReminders() != null && !enrichment.getReminders().isEmpty()) {
                    PIPELINE_LOG.info("[{}]   - REMINDERS: {} items", requestId, enrichment.getReminders().size());
                    for (String reminder : enrichment.getReminders()) {
                        PIPELINE_LOG.debug("[{}]     • {}", requestId, reminder);
                    }
                }
                if (enrichment.getBackground() != null && !enrichment.getBackground().isEmpty()) {
                    PIPELINE_LOG.info("[{}]   - BACKGROUND: {} items", requestId, enrichment.getBackground().size());
                    for (String bg : enrichment.getBackground()) {
                        PIPELINE_LOG.debug("[{}]     • {}", requestId, bg);
                    }
                }
                
                // Also categorize the content
                String categories = categorizeResponse(result);
                PIPELINE_LOG.info("[{}] Content analysis: {}", requestId, categories);
            } else if (result != null) {
                String categories = categorizeResponse(result);
                PIPELINE_LOG.info("[{}] Response categories: {}", requestId, categories);
            }
        } catch (Exception e) {
            PIPELINE_LOG.error("[{}] STEP 6: RESPONSE CATEGORIZATION - Failed to log: {}", 
                requestId, e.getMessage());
        }
    }
    
    /**
     * Log citation generation
     */
    @Around("execution(* com.williamcallahan.javachat.service.ChatService.citationsFor(..))")
    public Object logCitationGeneration(ProceedingJoinPoint joinPoint) throws Throwable {
        String requestId = REQUEST_ID.get();
        long startTime = System.currentTimeMillis();
        
        PIPELINE_LOG.info("[{}] STEP 7: CITATION GENERATION - Starting", requestId);
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            if (result instanceof java.util.List) {
                PIPELINE_LOG.info("[{}] STEP 7: CITATION GENERATION - Generated {} citations in {}ms", 
                    requestId, ((java.util.List<?>) result).size(), duration);
            }
            
            return result;
        } catch (Exception e) {
            PIPELINE_LOG.error("[{}] STEP 7: CITATION GENERATION - Failed: {}", 
                requestId, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Helper method to analyze query intent
     */
    private String analyzeIntent(String query) {
        query = query.toLowerCase();
        
        if (query.contains("how") || query.contains("what") || query.contains("explain")) {
            return "EXPLANATION";
        } else if (query.contains("example") || query.contains("show")) {
            return "EXAMPLE";
        } else if (query.contains("compare") || query.contains("difference")) {
            return "COMPARISON";
        } else if (query.contains("best") || query.contains("recommend")) {
            return "RECOMMENDATION";
        } else if (query.contains("debug") || query.contains("error") || query.contains("fix")) {
            return "TROUBLESHOOTING";
        } else {
            return "GENERAL_QUERY";
        }
    }
    
    /**
     * Helper method to categorize response
     */
    private String categorizeResponse(Object response) {
        Map<String, Boolean> categories = new HashMap<>();
        
        String responseStr = response.toString().toLowerCase();
        
        categories.put("HELPFUL_REMINDERS", 
            responseStr.contains("remember") || responseStr.contains("note") || responseStr.contains("important"));
        categories.put("CODE_EXAMPLES", 
            responseStr.contains("```") || responseStr.contains("code") || responseStr.contains("example"));
        categories.put("BEST_PRACTICES", 
            responseStr.contains("best practice") || responseStr.contains("recommend") || responseStr.contains("should"));
        categories.put("WARNINGS", 
            responseStr.contains("warning") || responseStr.contains("caution") || responseStr.contains("avoid"));
        categories.put("DOCUMENTATION_REFS", 
            responseStr.contains("documentation") || responseStr.contains("see") || responseStr.contains("refer"));
        categories.put("PERFORMANCE_TIPS", 
            responseStr.contains("performance") || responseStr.contains("optimize") || responseStr.contains("efficient"));
        
        try {
            return objectMapper.writeValueAsString(categories);
        } catch (JsonProcessingException e) {
            return categories.toString();
        }
    }
    
    /**
     * Log pipeline summary at the end
     */
    @AfterReturning(
        pointcut = "execution(* com.williamcallahan.javachat.web.ChatController.stream(..))",
        returning = "result"
    )
    public void logPipelineSummary(JoinPoint joinPoint, Object result) {
        String requestId = REQUEST_ID.get();
        
        PIPELINE_LOG.info("[{}] ============================================", requestId);
        PIPELINE_LOG.info("[{}] PIPELINE COMPLETE - All steps processed", requestId);
        PIPELINE_LOG.info("[{}] ============================================", requestId);
        
        // Clear the request ID for this thread
        REQUEST_ID.remove();
    }
}