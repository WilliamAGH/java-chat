package com.williamcallahan.javachat.logging;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Comprehensive logging aspect for all processing steps in the RAG pipeline.
 * Logs each step with detailed information for debugging and monitoring.
 */
@Aspect
@Component
public class ProcessingLogger {
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
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
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        long startTime = System.currentTimeMillis();

        PIPELINE_LOG.info("[{}] STEP 1: EMBEDDING GENERATION - Starting", requestToken);

        Object result = joinPoint.proceed();
        long duration = System.currentTimeMillis() - startTime;

        PIPELINE_LOG.info("[{}] STEP 1: EMBEDDING GENERATION - Completed in {}ms",
            requestToken, duration);

        return result;
    }

    /**
     * Logs embedding generation failures without masking the underlying exception.
     */
    @AfterThrowing(
        pointcut = "@annotation(org.springframework.web.bind.annotation.PostMapping) && " +
            "execution(* com.williamcallahan.javachat.service.*EmbeddingModel.embed(..))",
        throwing = "exception"
    )
    public void logEmbeddingGenerationFailure(Throwable exception) {
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        PIPELINE_LOG.error("[{}] STEP 1: EMBEDDING GENERATION - Failed (exception type: {})",
            requestToken, exception.getClass().getSimpleName());
    }
    
    /**
     * Log document parsing - DISABLED to avoid excessive logging during startup
     * The ChunkProcessingService processes thousands of documents on startup
     */
    // @Around("execution(* com.williamcallahan.javachat.service.ChunkProcessingService.process*(..))")
    public Object logDocumentParsing(ProceedingJoinPoint joinPoint) throws Throwable {
        // This method is disabled to prevent flooding logs during document processing
        // Uncomment the @Around annotation to re-enable if needed for debugging
        return joinPoint.proceed();
    }
    
    /**
     * Log query intent interpretation
     */
    @Before("execution(* com.williamcallahan.javachat.service.ChatService.*(..)) && " +
            "args(query,..)")
    public void logQueryIntentInterpretation(JoinPoint joinPoint, Object query) {
        int requestToken = Objects.hashCode(REQUEST_ID.get());

        PIPELINE_LOG.info("[{}] STEP 3: QUERY INTENT INTERPRETATION - Starting", requestToken);
    }
    
    /**
     * Log RAG retrieval
     */
    @Around("execution(* com.williamcallahan.javachat.service.RetrievalService.retrieve*(..))")
    public Object logRAGRetrieval(ProceedingJoinPoint joinPoint) throws Throwable {
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        long startTime = System.currentTimeMillis();
        
        PIPELINE_LOG.info("[{}] STEP 4: RAG RETRIEVAL - Starting vector search", requestToken);
        
        Object result = joinPoint.proceed();
        long duration = System.currentTimeMillis() - startTime;

        PIPELINE_LOG.info("[{}] STEP 4: RAG RETRIEVAL - Retrieved documents in {}ms",
            requestToken, duration);

        // Log retrieved document count if available
        if (result instanceof java.util.List) {
            PIPELINE_LOG.info("[{}] Retrieved documents", requestToken);
        }

        return result;
    }

    /**
     * Logs RAG retrieval failures without masking the underlying exception.
     */
    @AfterThrowing(
        pointcut = "execution(* com.williamcallahan.javachat.service.RetrievalService.retrieve*(..))",
        throwing = "exception"
    )
    public void logRagRetrievalFailure(Throwable exception) {
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        PIPELINE_LOG.error("[{}] STEP 4: RAG RETRIEVAL - Failed (exception type: {})",
            requestToken, exception.getClass().getSimpleName());
    }
    
    /**
     * Log LLM interaction
     */
    @Around("execution(* com.williamcallahan.javachat.service.ChatService.streamAnswer(..))")
    public Object logLLMInteraction(ProceedingJoinPoint joinPoint) throws Throwable {
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        long startTime = System.currentTimeMillis();

        PIPELINE_LOG.info("[{}] STEP 5: LLM INTERACTION - Sending to LLM", requestToken);

        Object result = joinPoint.proceed();
        long duration = System.currentTimeMillis() - startTime;

        PIPELINE_LOG.info("[{}] STEP 5: LLM INTERACTION - Response streaming started in {}ms",
            requestToken, duration);

        return result;
    }

    /**
     * Logs LLM interaction failures without masking the underlying exception.
     */
    @AfterThrowing(
        pointcut = "execution(* com.williamcallahan.javachat.service.ChatService.streamAnswer(..))",
        throwing = "exception"
    )
    public void logLlmInteractionFailure(Throwable exception) {
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        PIPELINE_LOG.error("[{}] STEP 5: LLM INTERACTION - Failed (exception type: {})",
            requestToken, exception.getClass().getSimpleName());
    }
    
    /**
     * Log response categorization
     */
    @AfterReturning(
        pointcut = "execution(* com.williamcallahan.javachat.service.EnrichmentService.enrich*(..))",
        returning = "result"
    )
    public void logResponseCategorization(JoinPoint joinPoint, Object result) {
        int requestToken = Objects.hashCode(REQUEST_ID.get());

        PIPELINE_LOG.info("[{}] STEP 6: RESPONSE CATEGORIZATION - Processing enrichments", requestToken);

        try {
            if (result instanceof com.williamcallahan.javachat.model.Enrichment) {
                PIPELINE_LOG.info("[{}] Enrichment categories extracted", requestToken);
            } else if (result != null) {
                PIPELINE_LOG.info("[{}] Response categories computed", requestToken);
            }
        } catch (RuntimeException runtimeException) {
            PIPELINE_LOG.error("[{}] STEP 6: RESPONSE CATEGORIZATION - Failed (runtime exception)", requestToken);
        } catch (Error fatalError) {
            PIPELINE_LOG.error("[{}] STEP 6: RESPONSE CATEGORIZATION - Failed (error)", requestToken);
        }
    }
    
    /**
     * Log citation generation
     */
    @Around("execution(* com.williamcallahan.javachat.service.ChatService.citationsFor(..))")
    public Object logCitationGeneration(ProceedingJoinPoint joinPoint) throws Throwable {
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        long startTime = System.currentTimeMillis();

        PIPELINE_LOG.info("[{}] STEP 7: CITATION GENERATION - Starting", requestToken);

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            if (result instanceof java.util.List) {
                PIPELINE_LOG.info("[{}] STEP 7: CITATION GENERATION - Completed in {}ms", requestToken, duration);
            }

            return result;
        } catch (RuntimeException runtimeException) {
            PIPELINE_LOG.error("[{}] STEP 7: CITATION GENERATION - Failed (runtime exception)", requestToken);
            throw runtimeException;
        } catch (Error fatalError) {
            PIPELINE_LOG.error("[{}] STEP 7: CITATION GENERATION - Failed (error)", requestToken);
            throw fatalError;
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
        int requestToken = Objects.hashCode(REQUEST_ID.get());
        
        PIPELINE_LOG.info("[{}] ============================================", requestToken);
        PIPELINE_LOG.info("[{}] PIPELINE COMPLETE - All steps processed", requestToken);
        PIPELINE_LOG.info("[{}] ============================================", requestToken);
        
        // Clear the request ID for this thread
        REQUEST_ID.remove();
    }
}
