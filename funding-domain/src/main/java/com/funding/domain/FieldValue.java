package com.funding.domain;

import java.math.BigDecimal;

/** One extracted field: raw string value as OCR wrote it, and its confidence (0.00–1.00). */
public record FieldValue(String raw, BigDecimal confidence) {}
