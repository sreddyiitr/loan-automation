package com.funding.domain;

import java.math.BigDecimal;

/** One extracted field row as stored (before per-key resolution). */
public record ExtractedField(String key, String raw, BigDecimal confidence) {}
