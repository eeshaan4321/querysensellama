package com.querysense.backend.exception;

/** Thrown when an uploaded schema cannot be accepted. */
public class SchemaUploadException extends RuntimeException {

    public SchemaUploadException(String message) {
        super(message);
    }

    public SchemaUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
