package com.querysense.backend.optimization;

/** One rewrite rule that fired, and why. */
public record RewriteChange(String ruleId, String explanation) {}
