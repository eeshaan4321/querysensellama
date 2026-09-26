package com.querysense.backend.dialect;

/** An implementation that serves exactly one dialect. */
public interface DialectAware {
    Dialect dialect();
}
