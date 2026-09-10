package com.querysense.backend.exception;

/**
 * SchemaUploadException
 *
 * Carries a message written for the person who uploaded the file: what was
 * wrong and, where there is one, what to do about it.
 *
 * GlobalExceptionHandler puts that message straight into the response body
 * and the frontend renders it directly — the same contract
 * LlmUnavailableException already uses to name the exact `ollama` command
 * to run. A vaguer message here costs the user the only thing that tells
 * them how to proceed.
 */
public class SchemaUploadException extends RuntimeException {

    public SchemaUploadException(String message) {
        super(message);
    }

    public SchemaUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
