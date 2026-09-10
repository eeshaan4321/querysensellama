package com.querysense.backend.service;

import com.querysense.backend.dto.QueryResponse;

/**
 * QueryGenerationService — UPDATED
 *
 * WHY THIS IS AN INTERFACE (not a class):
 * The Controller depends on this interface, not on the implementation.
 * This is the Dependency Inversion Principle — high-level modules
 * (Controller) should not depend on low-level modules (the Impl).
 * Both should depend on abstractions (this interface).
 *
 * Practical benefit: if we ever want a MockQueryGenerationService for
 * testing, we just create a second class that implements this interface.
 * The controller doesn't change at all.
 *
 * Updated: added databaseType parameter to match the Impl.
 */
public interface QueryGenerationService {

    QueryResponse generateQuery(String input, String databaseType);
}