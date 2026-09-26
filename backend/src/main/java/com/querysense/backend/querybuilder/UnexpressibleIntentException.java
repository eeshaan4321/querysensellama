package com.querysense.backend.querybuilder;

/** An intent the dialect cannot express faithfully; the message is the decline reason. */
public class UnexpressibleIntentException extends RuntimeException {
    public UnexpressibleIntentException(String reason) {
        super(reason);
    }
}
