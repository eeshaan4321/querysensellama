package com.querysense.backend.querybuilder;

/** A reason the rule engine declines, raised from inside a helper and turned into a decline by the parser. */
class DeclineException extends RuntimeException {

    DeclineException(String message) {
        super(message);
    }
}
