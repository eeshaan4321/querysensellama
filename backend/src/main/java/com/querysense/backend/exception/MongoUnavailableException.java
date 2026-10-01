package com.querysense.backend.exception;

/** Thrown when MongoDB cannot be reached for a request that needs it. */
public class MongoUnavailableException extends RuntimeException {

    public MongoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
