package com.querysense.backend.dto;

import java.util.List;

/**
 * One table as the frontend sees it.
 *
 * rowCount is 0 for the built-in schema: nothing measures the live demo
 * tables at startup, and reporting a guess would be worse than reporting
 * nothing. The UI renders no count rather than a wrong one.
 */
public record SchemaTableView(String name, List<String> columns, int rowCount) {}
