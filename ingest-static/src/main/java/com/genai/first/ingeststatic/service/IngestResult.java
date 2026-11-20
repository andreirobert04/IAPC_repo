package com.genai.first.ingeststatic.service;

/**
 * Outcome of ingesting one file.
 */
public enum IngestResult {
    /** first time this document is stored */
    NEW,
    /** checksum changed: old chunks replaced by a new version */
    UPDATED,
    /** same checksum and same embedding model: skipped */
    UNCHANGED,
    /** embedding model changed or forced re-index: chunks re-embedded with new model version */
    REINDEXED,
    /** removed from resources: all chunks deleted by doc_id */
    DELETED,
    /** reading/chunking/embedding/storing failed: the stored version (if any) is left as it was */
    FAILED
}
