package com.genai.first.ingeststatic.service;

/**
 * Outcome of ingesting one file.
 */
public enum IngestResult {
    /** first time this document is stored */
    NEW,
    /** checksum changed: old chunks replaced by a new version */
    UPDATED,
    /** same checksum as the stored version: skipped */
    UNCHANGED,
    /** reading/chunking/embedding/storing failed: the stored version (if any) is left as it was */
    FAILED
}
