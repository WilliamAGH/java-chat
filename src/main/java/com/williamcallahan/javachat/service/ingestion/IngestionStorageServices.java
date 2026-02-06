package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.QdrantCollectionRouter;
import org.springframework.stereotype.Service;

/**
 * Groups chunking, hashing, vector storage, and local marker dependencies for ingestion processors.
 *
 * <p>Bundles the services responsible for the storage pipeline (chunking content, computing
 * hashes, upserting hybrid vectors to Qdrant, routing to collections, and managing local
 * deduplication markers) so ingestion processors accept a single cohesive dependency.</p>
 *
 * @param hybridVector gRPC-based hybrid vector upsert service
 * @param chunks chunk processing pipeline
 * @param hasher content hash helper for deterministic vector IDs
 * @param localStore local snapshot and chunk storage
 * @param router routes documents to the correct Qdrant collection
 */
@Service
public record IngestionStorageServices(
        HybridVectorService hybridVector,
        ChunkProcessingService chunks,
        ContentHasher hasher,
        LocalStoreService localStore,
        QdrantCollectionRouter router) {}
