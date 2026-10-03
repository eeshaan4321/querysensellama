package com.querysense.backend.querybuilder;

/** A word reaches more than one schema item, so the rule engine declines rather than pick one. */
class AmbiguousWordException extends DeclineException {

    AmbiguousWordException(String message) {
        super(message);
    }
}
