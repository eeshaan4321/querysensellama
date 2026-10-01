package com.querysense.backend.dto;

import java.util.List;
import java.util.Map;

/** A table or collection of the active schema as shown to the frontend. */
public record SchemaTableView(
        String name,
        List<String> columns,
        int rowCount,
        List<String> embeddedArrays,
        Map<String, String> fieldTypes) {}
