package com.querysense.backend.dto;

import java.util.List;

/** A table of the active schema as shown to the frontend. */
public record SchemaTableView(String name, List<String> columns, int rowCount) {}
