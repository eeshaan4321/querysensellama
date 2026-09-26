package com.querysense.backend.mongo;

/** A mongosh query that could not be read, with the character position where reading stopped. */
public class MongoShellParseException extends RuntimeException {

    private final int position;

    public MongoShellParseException(String message, int position) {
        super(message);
        this.position = position;
    }

    public int position() {
        return position;
    }
}
