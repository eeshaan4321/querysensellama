package com.querysense.backend.schema.upload;

import java.util.List;
import java.util.Map;

/** How many rows or documents each table received, and what fell short. */
public record SeedReport(Map<String, Integer> rowCounts, List<String> warnings) {
}
